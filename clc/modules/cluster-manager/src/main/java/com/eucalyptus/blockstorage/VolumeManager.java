/*******************************************************************************
 *Copyright (c) 2009  Eucalyptus Systems, Inc.
 * 
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, only version 3 of the License.
 * 
 * 
 *  This file is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  for more details.
 * 
 *  You should have received a copy of the GNU General Public License along
 *  with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 *  Please contact Eucalyptus Systems, Inc., 130 Castilian
 *  Dr., Goleta, CA 93101 USA or visit <http://www.eucalyptus.com/licenses/>
 *  if you need additional information or have any questions.
 * 
 *  This file may incorporate work covered under the following copyright and
 *  permission notice:
 * 
 *    Software License Agreement (BSD License)
 * 
 *    Copyright (c) 2008, Regents of the University of California
 *    All rights reserved.
 * 
 *    Redistribution and use of this software in source and binary forms, with
 *    or without modification, are permitted provided that the following
 *    conditions are met:
 * 
 *      Redistributions of source code must retain the above copyright notice,
 *      this list of conditions and the following disclaimer.
 * 
 *      Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 * 
 *    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 *    IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 *    TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 *    PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 *    OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 *    EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 *    PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 *    PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 *    LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 *    NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *    SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. USERS OF
 *    THIS SOFTWARE ACKNOWLEDGE THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE
 *    LICENSED MATERIAL, COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS
 *    SOFTWARE, AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 *    IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA, SANTA
 *    BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY, WHICH IN
 *    THE REGENTS' DISCRETION MAY INCLUDE, WITHOUT LIMITATION, REPLACEMENT
 *    OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO IDENTIFIED, OR
 *    WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT NEEDED TO COMPLY WITH
 *    ANY SUCH LICENSES OR RIGHTS.
 *******************************************************************************
 * Author: chris grzegorczyk <grze@eucalyptus.com>
 */

package com.eucalyptus.blockstorage;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import javax.persistence.EntityTransaction;
import org.apache.log4j.Logger;
import com.eucalyptus.auth.AuthException;
import com.eucalyptus.auth.principal.AccountFullName;
import com.eucalyptus.auth.principal.UserFullName;
import com.eucalyptus.cloud.CloudMetadatas;
import com.eucalyptus.cluster.Cluster;
import com.eucalyptus.cluster.Clusters;
import com.eucalyptus.cluster.callback.VolumeAttachCallback;
import com.eucalyptus.cluster.callback.VolumeDetachCallback;
import com.eucalyptus.component.Partition;
import com.eucalyptus.component.Partitions;
import com.eucalyptus.component.ServiceConfiguration;
import com.eucalyptus.component.Topology;
import com.eucalyptus.component.id.ClusterController;
import com.eucalyptus.component.id.Storage;
import com.eucalyptus.context.Context;
import com.eucalyptus.context.Contexts;
import com.eucalyptus.entities.Entities;
import com.eucalyptus.entities.EntityWrapper;
import com.eucalyptus.entities.TransactionException;
import com.eucalyptus.entities.Transactions;
import com.eucalyptus.event.EventFailedException;
import com.eucalyptus.event.ListenerRegistry;
import com.eucalyptus.records.EventClass;
import com.eucalyptus.records.EventRecord;
import com.eucalyptus.records.EventType;
import com.eucalyptus.records.Logs;
import com.eucalyptus.reporting.event.StorageEvent;
import com.eucalyptus.util.EucalyptusCloudException;
import com.eucalyptus.util.Exceptions;
import com.eucalyptus.util.RestrictedTypes;
import com.eucalyptus.util.async.AsyncRequests;
import com.eucalyptus.vm.VmInstance;
import com.eucalyptus.vm.VmInstance.VmState;
import com.eucalyptus.vm.VmInstances;
import com.eucalyptus.vm.VmVolumeAttachment;
import com.eucalyptus.ws.client.ServiceDispatcher;
import com.google.common.base.Function;
import com.google.common.base.Predicates;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;
import edu.ucsb.eucalyptus.msgs.AttachStorageVolumeResponseType;
import edu.ucsb.eucalyptus.msgs.AttachStorageVolumeType;
import edu.ucsb.eucalyptus.msgs.AttachVolumeResponseType;
import edu.ucsb.eucalyptus.msgs.AttachVolumeType;
import edu.ucsb.eucalyptus.msgs.AttachedVolume;
import edu.ucsb.eucalyptus.msgs.CreateVolumeResponseType;
import edu.ucsb.eucalyptus.msgs.CreateVolumeType;
import edu.ucsb.eucalyptus.msgs.DeleteStorageVolumeResponseType;
import edu.ucsb.eucalyptus.msgs.DeleteStorageVolumeType;
import edu.ucsb.eucalyptus.msgs.DeleteVolumeResponseType;
import edu.ucsb.eucalyptus.msgs.DeleteVolumeType;
import edu.ucsb.eucalyptus.msgs.DescribeVolumesResponseType;
import edu.ucsb.eucalyptus.msgs.DescribeVolumesType;
import edu.ucsb.eucalyptus.msgs.DetachStorageVolumeType;
import edu.ucsb.eucalyptus.msgs.DetachVolumeResponseType;
import edu.ucsb.eucalyptus.msgs.DetachVolumeType;

public class VolumeManager {
  private static final int VOL_CREATE_RETRIES = 10;
  private static Logger    LOG                = Logger.getLogger( VolumeManager.class );
  
  public CreateVolumeResponseType CreateVolume( final CreateVolumeType request ) throws EucalyptusCloudException, AuthException {
    Context ctx = Contexts.lookup( );
    Long volSize = request.getSize( ) != null
      ? Long.parseLong( request.getSize( ) )
      : null;
    final String snapId = request.getSnapshotId( );
    String partition = request.getAvailabilityZone( );
    
    if ( ( request.getSnapshotId( ) == null && request.getSize( ) == null ) ) {
      throw new EucalyptusCloudException( "One of size or snapshotId is required as a parameter." );
    }
    
    if ( snapId != null ) {
      try {
        Transactions.find( Snapshot.named( null, snapId ) );
      } catch ( ExecutionException ex ) {
        throw new EucalyptusCloudException( "Failed to create volume because the referenced snapshot id is invalid: " + snapId );
      }
    }
    final Integer newSize = new Integer( request.getSize( ) != null
                                   ? request.getSize( )
                                     : "-1" );
    Exception lastEx = null;
    for ( int i = 0; i < VOL_CREATE_RETRIES; i++ ) {
      try {
        final ServiceConfiguration sc = Topology.lookup( Storage.class, Partitions.lookupByName( partition ) );
        final UserFullName owner = ctx.getUserFullName( );
        Function<Long,Volume> allocator = new Function<Long,Volume>() {

          @Override
          public Volume apply( Long size ) {
            try {
              return Volumes.createStorageVolume( sc, owner, snapId, Ints.checkedCast( size ), request );
            } catch ( ExecutionException ex ) {
              throw new RuntimeException( ex );
            }
          }};
        Volume newVol = RestrictedTypes.allocateMeasurableResource( newSize.longValue( ), allocator );
        CreateVolumeResponseType reply = request.getReply( );
        reply.setVolume( newVol.morph( new edu.ucsb.eucalyptus.msgs.Volume( ) ) );
        return reply;
      } catch ( RuntimeException ex ) {
        LOG.error( ex, ex );
        if ( !( ex.getCause( ) instanceof ExecutionException ) ) {
          throw ex;
        } else {
          lastEx = ex;
        }
      }
    }
    throw new EucalyptusCloudException( "Failed to create volume after " + VOL_CREATE_RETRIES + " because of: " + lastEx, lastEx );
  }
  
  public DeleteVolumeResponseType DeleteVolume( DeleteVolumeType request ) throws EucalyptusCloudException {
    DeleteVolumeResponseType reply = ( DeleteVolumeResponseType ) request.getReply( );
    Context ctx = Contexts.lookup( );
    reply.set_return( false );
    
    EntityWrapper<Volume> db = EntityWrapper.get( Volume.class );
    boolean reallyFailed = false;
    try {
      Volume vol = db.getUnique( Volume.named( ctx.getUserFullName( ).asAccountFullName( ), request.getVolumeId( ) ) );
      if ( !RestrictedTypes.filterPrivileged( ).apply( vol ) ) {
        throw new EucalyptusCloudException( "Not authorized to delete volume by " + ctx.getUser( ).getName( ) );
      }
      for ( VmInstance vm : VmInstances.list( Predicates.not( VmState.TERMINATED ) ) ) {
        try {
          vm.lookupVolumeAttachment( request.getVolumeId( ) );
          db.rollback( );
          return reply;
        } catch ( NoSuchElementException ex ) {
          /** no such volume attached, move along... **/
        }
      }
      if ( State.FAIL.equals( vol.getState( ) ) ) {
        db.delete( vol );
        db.commit( );
        return reply;
      }
      ServiceConfiguration sc = Topology.lookup( Storage.class, Partitions.lookupByName( vol.getPartition( ) ) );
      DeleteStorageVolumeResponseType scReply = ServiceDispatcher.lookup( sc ).send( new DeleteStorageVolumeType( vol.getDisplayName( ) ) );
      if ( scReply.get_return( ) ) {
        vol.setState( State.ANNIHILATING );
        db.commit( );
        fireDeleteEvent( vol );
      } else {
        reallyFailed = true;
        throw new EucalyptusCloudException( "Storage Controller returned false:  Contact the administrator to report the problem." );
      }
    } catch ( EucalyptusCloudException e ) {
      LOG.debug( e, e );
      db.rollback( );
      if ( reallyFailed ) {
        throw e;
      } else {
        return reply;
      }
    }
    reply.set_return( true );
    return reply;
  }
  
  public DescribeVolumesResponseType DescribeVolumes( DescribeVolumesType request ) throws Exception {
    final DescribeVolumesResponseType reply = ( DescribeVolumesResponseType ) request.getReply( );
    final Context ctx = Contexts.lookup( );
    final boolean showAll = request.getVolumeSet( ).remove( "verbose" );
    final AccountFullName ownerFullName = ( ctx.hasAdministrativePrivileges( ) && showAll ) ? null : ctx.getUserFullName( ).asAccountFullName( );
    final Set<String> volumeIds = Sets.newHashSet( );
    if ( !request.getVolumeSet( ).isEmpty( ) ) {
      volumeIds.addAll( request.getVolumeSet( ) );
    }
    
    final Function<Set<String>, List<Volume>> lookupVolumes = new Function<Set<String>, List<Volume>>( ) {
      public List<Volume> apply( final Set<String> input ) {
        List<Volume> res = Lists.newArrayList( );
        if ( input.isEmpty( ) ) {
          List<Volume> found = Entities.query( Volume.named( ownerFullName, null ) );
          res.addAll( Collections2.filter( found, RestrictedTypes.filterPrivileged( ) ) );
        } else {
          for ( String s : input ) {
            try {
              Volume foundVol = Entities.uniqueResult( Volume.named( ownerFullName, s ) );
              if ( RestrictedTypes.filterPrivileged( ).apply( foundVol ) ) {
                res.add( foundVol );
              }
            } catch ( NoSuchElementException ex ) {
            } catch ( TransactionException ex ) {
              throw Exceptions.toUndeclared( ex );
            }
          }
        }
        return res;
      }
    };
    
    EntityTransaction db = Entities.get( Volume.class );
    try {
      List<Volume> volumes = lookupVolumes.apply( volumeIds );
      List<Volume> describeVolumes = Lists.newArrayList( );
      for ( Volume v : volumes ) {
        if ( !State.ANNIHILATED.equals( v.getState( ) ) ) {
          describeVolumes.add( v );
        } else {
          fireDeleteEvent( v );
          Entities.delete( v );
        }
      }
      reply.getVolumeSet( ).addAll( StorageUtil.getVolumeReply( describeVolumes ) );
      db.commit( );
    } catch ( Exception ex ) {
      Logs.extreme( ).error( ex, ex );
      db.rollback( );
      throw ex;
    }
    return reply;
  }

  private static void fireDeleteEvent( Volume v ) {
    try {
      ListenerRegistry.getInstance( ).fireEvent( new StorageEvent( StorageEvent.EventType.EbsVolume, false, v.getSize( ),
                                                                   v.getOwnerUserId( ), v.getOwnerUserName( ),
                                                                   v.getOwnerAccountNumber( ), v.getOwnerAccountName( ),
                                                                   v.getScName( ), v.getPartition( ) ) );
    } catch ( EventFailedException ex ) {
      LOG.error( ex );
      Logs.extreme( ).error( ex, ex );
    }
  }
  
  public AttachVolumeResponseType AttachVolume( AttachVolumeType request ) throws EucalyptusCloudException {
    AttachVolumeResponseType reply = ( AttachVolumeResponseType ) request.getReply( );
    final String deviceName = request.getDevice( );
    final String volumeId = request.getVolumeId( );
    final Context ctx = Contexts.lookup( );
    
    if ( request.getDevice( ) == null || request.getDevice( ).endsWith( "sda" ) || request.getDevice( ).endsWith( "sdb" ) ) {
      throw new EucalyptusCloudException( "Invalid device name specified: " + request.getDevice( ) );
    }
    VmInstance vm = null;
    try {
      vm = RestrictedTypes.doPrivileged( request.getInstanceId( ), VmInstance.class );
    } catch ( NoSuchElementException ex ) {
      LOG.debug( ex, ex );
      throw new EucalyptusCloudException( "Instance does not exist: " + request.getInstanceId( ), ex );
    } catch ( Exception ex ) {
      LOG.debug( ex, ex );
      throw new EucalyptusCloudException( ex.getMessage( ), ex );
    }
    AccountFullName ownerFullName = ctx.getUserFullName( ).asAccountFullName( );
    Volume volume = Volumes.lookup( ownerFullName, volumeId );
    if ( !RestrictedTypes.filterPrivileged( ).apply( volume ) ) {
      throw new EucalyptusCloudException( "Not authorized to attach volume " + request.getVolumeId( ) + " by " + ctx.getUser( ).getName( ) );
    }
    Cluster cluster = null;
    try {
      ServiceConfiguration ccConfig = Topology.lookup( ClusterController.class, vm.lookupPartition( ) );
      cluster = Clusters.lookup( ccConfig );
    } catch ( NoSuchElementException e ) {
      LOG.debug( e, e );
      throw new EucalyptusCloudException( "Cluster does not exist in partition: " + vm.getPartition( ) );
    }
    try {
      vm.lookupVolumeAttachmentByDevice( deviceName );
      throw new EucalyptusCloudException( "Already have a device attached to: " + request.getDevice( ) );
    } catch ( NoSuchElementException ex1 ) {
      /** no attachment **/
    }
    try {
      VmInstances.lookupVolumeAttachment( volumeId );
      throw new EucalyptusCloudException( "Volume already attached: " + request.getVolumeId( ) );
    } catch ( Exception ex1 ) {
    }

    Partition volPartition = Partitions.lookupByName( volume.getPartition( ) );
    ServiceConfiguration sc = Topology.lookup( Storage.class, volPartition );
    ServiceConfiguration scVm = Topology.lookup( Storage.class, cluster.getConfiguration( ).lookupPartition( ) );
    if ( !sc.equals( scVm ) ) {
      throw new EucalyptusCloudException( "Can only attach volumes in the same zone: " + request.getVolumeId( ) );
    } else if ( "invalid".equals( volume.getRemoteDevice( ) ) ) {
      throw new EucalyptusCloudException( "Volume is not yet available: " + request.getVolumeId( ) );
    }
    
    AttachStorageVolumeResponseType scAttachResponse;
    try {
      AttachStorageVolumeType req = new AttachStorageVolumeType( cluster.getNode( vm.getServiceTag( ) ).getIqn( ), volume.getDisplayName( ) );
      scAttachResponse = AsyncRequests.sendSync( sc, req );
    } catch ( Exception e ) {
      LOG.debug( e, e );
      throw new EucalyptusCloudException( e.getMessage( ) );
    }
    request.setRemoteDevice( scAttachResponse.getRemoteDeviceString( ) );

    AttachedVolume attachVol = new AttachedVolume( volume.getDisplayName( ), vm.getInstanceId( ), request.getDevice( ), request.getRemoteDevice( ) );
    vm.addTransientVolume( deviceName, volume );
    AsyncRequests.newRequest( new VolumeAttachCallback( request, attachVol ) ).dispatch( cluster.getConfiguration( ) );
    
    EventRecord.here( VolumeManager.class, EventClass.VOLUME, EventType.VOLUME_ATTACH )
               .withDetails( volume.getOwner( ).toString( ), volume.getDisplayName( ), "instance", vm.getInstanceId( ) )
               .withDetails( "partition", vm.getPartition( ).toString( ) ).info( );
    reply.setAttachedVolume( attachVol );
    return reply;
  }

  public DetachVolumeResponseType detach( DetachVolumeType request ) throws EucalyptusCloudException {
    DetachVolumeResponseType reply = ( DetachVolumeResponseType ) request.getReply( );
    Context ctx = Contexts.lookup( );
    
    Volume vol = null;
    EntityWrapper<Volume> db = EntityWrapper.get( Volume.class );
    try {
      vol = db.getUnique( Volume.named( ctx.getUserFullName( ).asAccountFullName( ), request.getVolumeId( ) ) );
    } catch ( EucalyptusCloudException e ) {
      LOG.debug( e, e );
      db.rollback( );
      throw new EucalyptusCloudException( "Volume does not exist: " + request.getVolumeId( ) );
    }
    db.commit( );
    if ( !RestrictedTypes.filterPrivileged( ).apply( vol ) ) {
      throw new EucalyptusCloudException( "Not authorized to detach volume " + request.getVolumeId( ) + " by " + ctx.getUser( ).getName( ) );
    }
    
    VmInstance vm = null;
    AttachedVolume volume = null;
    try {
      VmVolumeAttachment vmVolAttach = VmInstances.lookupVolumeAttachment( request.getVolumeId( ) );
      volume = VmVolumeAttachment.asAttachedVolume( vmVolAttach.getVmInstance( ) ).apply( vmVolAttach );
      vm = vmVolAttach.getVmInstance( );
    } catch ( NoSuchElementException ex ) {
      /** no such attachment **/
    }
    if ( volume == null ) {
      throw new EucalyptusCloudException( "Volume is not attached: " + request.getVolumeId( ) );
    }
    if ( !RestrictedTypes.filterPrivileged( ).apply( vm ) ) {
      throw new EucalyptusCloudException( "Not authorized to detach volume from instance " + request.getInstanceId( ) + " by " + ctx.getUser( ).getName( ) );
    }
    if ( !vm.getInstanceId( ).equals( request.getInstanceId( ) ) && request.getInstanceId( ) != null && !request.getInstanceId( ).equals( "" ) ) {
      throw new EucalyptusCloudException( "Volume is not attached to instance: " + request.getInstanceId( ) );
    }
    if ( request.getDevice( ) != null && !request.getDevice( ).equals( "" ) && !volume.getDevice( ).equals( request.getDevice( ) ) ) {
      throw new EucalyptusCloudException( "Volume is not attached to device: " + request.getDevice( ) );
    }
    
    Cluster cluster = null;
    ServiceConfiguration ccConfig = null;
    try {
      ccConfig = Topology.lookup( ClusterController.class, vm.lookupPartition( ) );
      cluster = Clusters.lookup( ccConfig );
    } catch ( NoSuchElementException e ) {
      LOG.debug( e, e );
      throw new EucalyptusCloudException( "Cluster does not exist in partition: " + vm.getPartition( ) );
    }
    ServiceConfiguration scVm;
    try {
      scVm = Topology.lookup( Storage.class, vm.lookupPartition( ) );
    } catch ( Exception ex ) {
      LOG.error( ex, ex );
      throw new EucalyptusCloudException( "Failed to lookup SC for partition: " + vm.getPartition( ), ex );
    }
    try {
      AsyncRequests.sendSync( scVm, new DetachStorageVolumeType( cluster.getNode( vm.getServiceTag( ) ).getIqn( ), volume.getVolumeId( ) ) );
    } catch ( Exception e ) {
      LOG.debug( e, e );
      throw new EucalyptusCloudException( e.getMessage( ) );
    }
    request.setVolumeId( volume.getVolumeId( ) );
    request.setRemoteDevice( volume.getRemoteDevice( ) );
    request.setDevice( volume.getDevice( ).replaceAll( "unknown,requested:", "" ) );
    request.setInstanceId( vm.getInstanceId( ) );
    AsyncRequests.newRequest( new VolumeDetachCallback( request ) ).dispatch( cluster.getConfiguration( ) );
    EventRecord.here( VolumeManager.class, EventClass.VOLUME, EventType.VOLUME_DETACH )
               .withDetails( vm.getOwner( ).toString( ), volume.getVolumeId( ), "instance", vm.getInstanceId( ) )
               .withDetails( "cluster", ccConfig.getFullName( ).toString( ) ).info( );
    volume.setStatus( "detaching" );
    reply.setDetachedVolume( volume );
    return reply;
  }
  
}
