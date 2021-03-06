/*************************************************************************
 * Copyright 2009-2016 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 ************************************************************************/
package com.eucalyptus.network.applicator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Date;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.apache.log4j.Logger;
import com.eucalyptus.cluster.NetworkInfo;
import com.eucalyptus.component.Faults;
import com.eucalyptus.component.id.Eucalyptus;
import com.eucalyptus.crypto.util.Timestamps;
import com.eucalyptus.network.NetworkGroups;
import com.eucalyptus.network.NetworkMode;
import com.eucalyptus.system.BaseDirectory;
import com.eucalyptus.util.Pair;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;

/**
 *
 */
public class AppliedVersionApplicator extends ModeSpecificApplicator {

  private static final String APPLIED_VERSION_FILE = "global_network_info.version";

  private static final Logger logger = Logger.getLogger( AppliedVersionApplicator.class );
  private static final AtomicReference<Pair<WatchService,WatchKey>> watchContext = new AtomicReference<>( );
  private static final AtomicReference<Pair<Long,String>> lastAppliedVersion = new AtomicReference<>( );
  private static final Supplier<String> faultSupplier = Suppliers.memoizeWithExpiration(
      () -> Faults.forComponent( Eucalyptus.class ).havingId( 1016 ).log( ),
      15, TimeUnit.MINUTES );

  public AppliedVersionApplicator( ) {
    super( EnumSet.of( NetworkMode.VPCMIDO ) );
  }

  @Override
  protected void modeApply(
      final NetworkMode mode,
      final ApplicatorContext context,
      final ApplicatorChain chain
  ) throws ApplicatorException {
    final NetworkInfo info = context.getNetworkInfo( );
    final Pair<Long,String> lastAppliedVersion = getLastAppliedVersion( );
    boolean alreadyApplied = false;
    if ( lastAppliedVersion != null ) {
      info.setAppliedTime( Timestamps.formatIso8601Timestamp( new Date( lastAppliedVersion.getLeft( ) ) ) );
      info.setAppliedVersion( lastAppliedVersion.getRight( ) );
      MarshallingApplicatorHelper.clearMarshalledNetworkInfoCache( context );
      alreadyApplied = info.getVersion( ).equals( lastAppliedVersion.getRight( ) );
    }

    // initial broadcast
    chain.applyNext( context );

    // wait for eucanetd to apply
    boolean applied = false;
    final Path path = BaseDirectory.RUN.getChildFile( APPLIED_VERSION_FILE ).toPath( );
    final long until = System.currentTimeMillis( ) + TimeUnit.SECONDS.toMillis( NetworkGroups.MAX_BROADCAST_APPLY );
    long now;
    waitloop:
    while( !alreadyApplied && ( now = System.currentTimeMillis( ) ) < until ) try {
      final WatchKey key = getWatchService( ).poll( until - now, TimeUnit.MILLISECONDS );
      if ( key != null ) try {
        for ( final WatchEvent<?> event: key.pollEvents( ) ) {
          if ( path.getFileName( ).equals( event.context( ) ) ) {
            try {
              final Pair<Long,String> appliedVersion = readAppliedVersion( path );
              if ( !info.getVersion( ).equals( appliedVersion.getRight( ) ) ) {
                continue waitloop; // wait until timeout
              }
              info.setAppliedTime( Timestamps.formatIso8601Timestamp( new Date( appliedVersion.getLeft( ) ) ) );
              info.setAppliedVersion( appliedVersion.getRight( ) );
              MarshallingApplicatorHelper.clearMarshalledNetworkInfoCache( context );
              applied = true;
              break waitloop;
            } catch ( IOException e ) {
              logger.error( "Error reading last applied network broadcast version" );
            }
          }
        }
      } finally {
        key.reset( );
      }
    } catch ( InterruptedException e ) {
      break;
    }

    // broadcast with updated info
    if ( applied ) {
      chain.applyNext( context );
    } else if ( !alreadyApplied ) {
      faultSupplier.get( );
    }
  }

  @Nullable
  private Pair<Long,String> getLastAppliedVersion( ) throws ApplicatorException {
    Pair<Long,String> lastApplied = lastAppliedVersion.get( );
    final Path path = BaseDirectory.RUN.getChildFile( APPLIED_VERSION_FILE ).toPath( );
    if ( lastApplied == null && path.toFile( ).canRead( ) ) {
      try {
        lastApplied = readAppliedVersion( path );
      } catch ( IOException e ) {
        throw new ApplicatorException( "Error reading applied version", e );
      }
    }
    return lastApplied;
  }

  private Pair<Long,String> readAppliedVersion( final Path path ) throws IOException {
    final String appliedVersion =
        com.google.common.io.Files.toString( path.toFile( ), StandardCharsets.UTF_8 ).trim( );
    final long appliedTime = System.currentTimeMillis( );
    final Pair<Long,String> appliedVersionPair = Pair.pair( appliedTime, appliedVersion );
    lastAppliedVersion.set( appliedVersionPair );
    return appliedVersionPair;
  }

  private WatchService getWatchService( ) throws ApplicatorException {
    Pair<WatchService,WatchKey> watchPair = watchContext.get( );
    if ( watchPair == null ) try {
      final Path path = BaseDirectory.RUN.getFile( ).toPath( );
      final WatchService watcher = path.getFileSystem( ).newWatchService( );
      final WatchKey watckKey = path.register( watcher,
          StandardWatchEventKinds.ENTRY_CREATE,
          StandardWatchEventKinds.ENTRY_MODIFY
      );
      watchPair = Pair.pair( watcher, watckKey );
      watchContext.set( watchPair );
    } catch ( IOException e ) {
      throw new ApplicatorException( "Error setting up file watch", e );
    }
    return watchPair.getLeft( );
  }
}
