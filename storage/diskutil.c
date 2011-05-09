// -*- mode: C; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil -*-
// vim: set softtabstop=4 shiftwidth=4 tabstop=4 expandtab:

/*
  Copyright (c) 2009  Eucalyptus Systems, Inc.

  This program is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, only version 3 of the License.

  This file is distributed in the hope that it will be useful, but WITHOUT
  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
  for more details.

  You should have received a copy of the GNU General Public License along
  with this program.  If not, see <http://www.gnu.org/licenses/>.

  Please contact Eucalyptus Systems, Inc., 130 Castilian
  Dr., Goleta, CA 93101 USA or visit <http://www.eucalyptus.com/licenses/>
  if you need additional information or have any questions.

  This file may incorporate work covered under the following copyright and
  permission notice:

  Software License Agreement (BSD License)

  Copyright (c) 2008, Regents of the University of California


  Redistribution and use of this software in source and binary forms, with
  or without modification, are permitted provided that the following
  conditions are met:

  Redistributions of source code must retain the above copyright notice,
  this list of conditions and the following disclaimer.

  Redistributions in binary form must reproduce the above copyright
  notice, this list of conditions and the following disclaimer in the
  documentation and/or other materials provided with the distribution.

  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
  IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
  TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
  PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
  OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
  EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
  PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
  PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
  LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
  NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. USERS OF
  THIS SOFTWARE ACKNOWLEDGE THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE
  LICENSED MATERIAL, COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS
  SOFTWARE, AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
  IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA, SANTA
  BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY, WHICH IN
  THE REGENTS’ DISCRETION MAY INCLUDE, WITHOUT LIMITATION, REPLACEMENT
  OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO IDENTIFIED, OR
  WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT NEEDED TO COMPLY WITH
  ANY SUCH LICENSES OR RIGHTS.
*/

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <getopt.h>
#include <string.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <stdarg.h>
#include <errno.h>
#include "misc.h" // logprintfl
#include "diskutil.h"
#include "eucalyptus.h"
#include "pthread.h"

enum { 
    MKSWAP=0, 
    MKEXT3,
    TUNE2FS,
    FILECMD, 
    LOSETUP, 
    MOUNT, 
    GRUB, 
    PARTED, 
    MV, 
    DD, 
    SYNC, 
    MKDIR, 
    CP, 
    RSYNC, 
    UMOUNT, 
    CAT, 
    CHOWN, 
    CHMOD, 
    ROOTWRAP, 
    MOUNTWRAP, 
    LASTHELPER
};

static char * helpers [LASTHELPER] = {
    "mkswap", 
    "mkfs.ext3", 
    "tune2fs",
    "file", 
    "losetup", 
    "mount", 
    "grub", 
    "parted", 
    "mv", 
    "dd", 
    "sync", 
    "mkdir", 
    "cp", 
    "rsync", 
    "umount", 
    "cat", 
    "chown", 
    "chmod", 
    "euca_rootwrap", 
    "euca_mountwrap"
};

static char * helpers_path [LASTHELPER];
static char * pruntf (char *format, ...);
static int initialized = 0;

int diskutil_init (void)
{
    int ret = 0;

    if (!initialized) {
        ret = verify_helpers (helpers, helpers_path, LASTHELPER);
        initialized = 1;
    }

    return ret;
}

int diskutil_cleanup (void)
{
    for (int i=0; i<LASTHELPER; i++) {
        free (helpers_path [i]);
    }
    return 0;
}

int diskutil_ddzero (const char * path, const long long sectors, boolean zero_fill)
{
    int ret = OK;
    char * output;

    long long count = 1;
    long long seek = sectors - 1;
    if (zero_fill) {
        count = sectors;
        seek = 0;
    }

    output = pruntf ("%s %s if=/dev/zero of=%s bs=512 seek=%lld count=%lld", helpers_path[ROOTWRAP], helpers_path[DD], path, seek, count);
    if (!output) {
        logprintfl (EUCAINFO, "ERROR: cannot create disk file %s\n", path);
        ret = ERROR;
    } else {
        free (output);
    }

    return ret;
}

int diskutil_dd (const char * in, const char * out, const int bs, const long long count)
{
    int ret = OK;
    char * output;

    logprintfl (EUCAINFO, "copying infile data to intermediate disk file...\n");
    output = pruntf("%s %s if=%s of=%s bs=%d count=%lld", helpers_path[ROOTWRAP], helpers_path[DD], in, out, bs, count);
    if (!output) {
        logprintfl (EUCAINFO, "ERROR: cannot copy '%s' to '%s'\n", in, out);
        ret = ERROR;
    } else {
        free (output);
    }

    return ret;
}

int diskutil_dd2 (const char * in, const char * out, const int bs, const long long count, const long long seek, const long long skip)
{
    int ret = OK;
    char * output;

    logprintfl (EUCAINFO, "copying data from %s to %s of %lld blocks (bs=%d), seeking %lld, skipping %lld\n", in, out, count, bs, seek, skip);
    output = pruntf("%s %s if=%s of=%s bs=%d count=%lld seek=%lld skip=%lld conv=notrunc,fsync", helpers_path[ROOTWRAP], helpers_path[DD], in, out, bs, count, seek, skip);
    if (!output) {
        logprintfl (EUCAINFO, "ERROR: cannot copy '%s' to '%s'\n", in, out);
        ret = ERROR;
    } else {
        free (output);
    }

    return ret;
}

int diskutil_mbr (const char * path, const char * type)
{
    int ret = OK;
    char * output;

    output = pruntf ("LD_PRELOAD='' %s %s --script %s mklabel %s", helpers_path[ROOTWRAP], helpers_path[PARTED], path, type);
    if (!output) {
        logprintfl (EUCAINFO, "ERROR: cannot create an MBR\n");
        ret = ERROR;
    } else {
        free (output);
    }

    return ret;
}

int diskutil_part (const char * path, char * part_type, const char * fs_type, const long long first_sector, const long long last_sector)
{
    int ret = OK;
    char * output;

    output = pruntf ("LD_PRELOAD='' %s %s --script %s mkpart %s %s %llds %llds", helpers_path[ROOTWRAP], helpers_path[PARTED], path, part_type, (fs_type)?(fs_type):(""), first_sector, last_sector);
    if (!output) {
        logprintfl (EUCAINFO, "ERROR: cannot add a partition\n");
        ret = ERROR;
    } else {
        free (output);
    }

    return ret;
}

int diskutil_loop (const char * path, const long long offset, char * lodev, int lodev_size)
{
    int found = 0;
    int done = 0;
    int ret = OK;
    char * output;

    for (int i=0; i<10; i++) {
        output = pruntf ("%s %s -f", helpers_path[ROOTWRAP], helpers_path[LOSETUP]);
        if (output==NULL) // there was a problem
            break;
        if (strstr (output, "/dev/loop")) {
            strncpy (lodev, output, lodev_size);
            char * ptr = strrchr (lodev, '\n');
            if (ptr) {
                *ptr = '\0';
                found = 1;
            }
        }
        free (output);

        if (found) {
            logprintfl (EUCADEBUG, "{%u} attaching to loop device '%s' at offset '%lld' file %s\n", (unsigned int)pthread_self(), lodev, offset, path);
            output = pruntf ("%s %s -o %lld %s %s", helpers_path[ROOTWRAP], helpers_path[LOSETUP], offset, lodev, path);
            if (output==NULL) {
                logprintfl (EUCAINFO, "WARNING: cannot attach %s to loop device %s (will retry)\n", path, lodev);
            } else {
                free (output);
                done = 1;
                break;
            }
        }

        sleep (3);
        found = 0;
    }
    if (!done) {
        logprintfl (EUCAINFO, "ERROR: cannot find free loop device or attach to one\n");
        ret = ERROR;
    }

    return ret;
}

int diskutil_unloop (const char * lodev)
{
    int ret = OK;
    char * output;

    logprintfl (EUCAINFO, "{%u} detaching from loop device '%s'\n", (unsigned int)pthread_self(), lodev);
    output = pruntf("%s %s", helpers_path[ROOTWRAP], helpers_path[SYNC]);
    if (output)
        free (output);
    output = pruntf("%s %s -d %s", helpers_path[ROOTWRAP], helpers_path[LOSETUP], lodev);
    if (!output) {
        logprintfl (EUCAINFO, "ERROR: cannot detach loop device\n");
        ret = ERROR;
    } else {
        free (output);
    }

    return ret;
}

int diskutil_mkswap (const char * lodev, const long long size_bytes)
{
    int ret = OK;
    char * output;

    output = pruntf ("%s %s %s %lld", helpers_path[ROOTWRAP], helpers_path[MKSWAP], lodev, size_bytes/1024);
    if (!output) {
        logprintfl (EUCAINFO, "ERROR: cannot format partition on '%s' as swap\n", lodev);
        ret = ERROR;
    } else {
        free (output);
    }

    return ret;
}

int diskutil_mkfs (const char * lodev, const long long size_bytes)
{
    int ret = OK;
    char * output;
    int block_size = 4096;

    output = pruntf ("%s %s -b %d %s %lld", helpers_path[ROOTWRAP], helpers_path[MKEXT3], block_size, lodev, size_bytes/block_size);
    if (!output) {
        logprintfl (EUCAINFO, "ERROR: cannot format partition on '%s' as ext3\n", lodev);
        ret = ERROR;
    } else {
        free (output);
    }

    return ret;
}

int diskutil_tune (const char * lodev)
{
    int ret = OK;
    char * output;

    output = pruntf ("%s %s %s -c 0 -i 0", helpers_path[ROOTWRAP], helpers_path[TUNE2FS], lodev);
    if (!output) {
        logprintfl (EUCAINFO, "ERROR: cannot tune file system on '%s'\n", lodev);
        ret = ERROR;
    } else {
        free (output);
    }

    return ret;
}

int diskutil_sectors (const char * path, const int part, long long * first, long long * last)
{
    int ret = ERROR;
    char * output;
    * first = 0L;
    * last = 0L;

    output = pruntf ("%s %s", helpers_path[FILECMD], path);
    if (!output) {
        logprintfl (EUCAINFO, "ERROR: failed to extract partition information for '%s'\n", path);
    } else {
        // parse the output, such as:
        // NAME: x86 boot sector;
        // partition 1: ID=0x83, starthead 1, startsector 63, 32769 sectors;
        // partition 2: ID=0x83, starthead 2, startsector 32832, 32769 sectors;
        // partition 3: ID=0x82, starthead 2, startsector 65601, 81 sectors
        boolean found = FALSE;
        char * section = strtok (output, ";"); // split by semicolon
        for (int p = 0; section != NULL; p++) {
            section = strtok (NULL, ";");
            if (section && p == part) {
                found = TRUE;
                break;
            }
        }
        if (found) {
            char * ss = strstr (section, "startsector");
            if (ss) {
                ss += strlen ("startsector ");
                char * comma = strstr (ss, ", ");
                if (comma) {
                    * comma = '\0';
                    comma += strlen (", ");
                    char * end = strstr (comma, " sectors");
                    if (end) {
                        * end = '\0';
                        * first = atoll (ss);
                        * last = * first + atoll (comma) - 1L;
                    }
                }
            }
        }

        free (output);
    }

    if ( * last > 0 )
        ret = OK;

    return ret;
}

int diskutil_mount (const char * dev, const char * mnt_pt)
{
    int ret = OK;
    char * output;

    output = pruntf ("%s %s mount %s %s", helpers_path[ROOTWRAP], helpers_path[MOUNTWRAP], dev, mnt_pt);
    if (!output) {
        logprintfl (EUCAINFO, "ERROR: cannot mount device '%s' on '%s'\n", dev, mnt_pt);
        ret = ERROR;
    } else {
        free (output);
    }

    return ret;
}

int diskutil_umount (const char * dev)
{
    int ret = OK;
    char * output;

    output = pruntf ("%s %s umount %s", helpers_path[ROOTWRAP], helpers_path[MOUNTWRAP], dev);
    if (!output) {
        logprintfl (EUCAINFO, "ERROR: cannot unmount device '%s'\n", dev);
        ret = ERROR;
    } else {
        free (output);
    }

    return ret;
}

int diskutil_write2file (const char * file, const char * str)
{
    int ret = OK;
    char tmpfile [] = "/tmp/euca-temp-XXXXXX";
    int fd = mkstemp (tmpfile);
    if (fd<0) {
        logprintfl (EUCAERROR, "error: failed to create temporary directory\n");
        return ERROR;
    }
    int size = strlen (str);
    if (write (fd, str, size) != size) {
        logprintfl (EUCAERROR, "error: failed to create temporary directory\n");
        ret = ERROR;
    } else {
        if (diskutil_cp (tmpfile, file) != OK) {
            logprintfl (EUCAERROR, "error: failed to copy temp file to destination (%s)\n", file);
            ret = ERROR;
        }
    }
    close (fd);

    return ret;
}

int diskutil_grub_files (const char * mnt_pt, const int part, const char * kernel, const char * ramdisk)
{
    int ret = OK;
    char * output;
    char * kfile;
    char * rfile;

    output = pruntf ("%s %s -p %s/boot/grub/", helpers_path[ROOTWRAP], helpers_path[MKDIR], mnt_pt);
    if (!output) {
        logprintfl (EUCAINFO, "ERROR: failed to create grub directory\n");
        return ERROR;
    }
    free (output);

    output = pruntf ("%s %s /boot/grub/*stage* %s/boot/grub", helpers_path[ROOTWRAP], helpers_path[CP], mnt_pt);
    if (!output) {
        logprintfl (EUCAINFO, "ERROR: failed to copy stage files into grub directory\n");
        return ERROR;
    }
    free (output);

    char * ptr = strrchr (kernel, '/');
    if (ptr) {
        kfile = strdup (ptr+1);
    } else {
        kfile = strdup (kernel);
    }

    if (ramdisk) {
        ptr = strrchr (ramdisk, '/');
        if (ptr) {
            rfile = strdup (ptr+1);
        } else {
            rfile = strdup (ramdisk);
        }
    }

    logprintfl (EUCAINFO, "installing kernel, ramdisk, and modules...\n");
    output = pruntf("%s %s %s %s/boot/%s", helpers_path[ROOTWRAP], helpers_path[CP], kernel, mnt_pt, kfile);
    if (!output) {
        logprintfl (EUCAINFO, "ERROR: failed to copy the kernel to boot directory\n");
        return ERROR;
    }
    free (output);

    if (ramdisk) {
        output = pruntf("%s %s %s %s/boot/%s", helpers_path[ROOTWRAP], helpers_path[CP], ramdisk, mnt_pt, rfile);
        if (!output) {
            logprintfl (EUCAINFO, "ERROR: failed to copy the ramdisk to boot directory\n");
            return ERROR;
        }
        free (output);
    }

    /*
      if (modules) {
      while(strlen(modules) && modules[strlen(modules)-1] == '/') {
      modules[strlen(modules)-1] = '\0';
      }
      output = pruntf("%s %s -az %s %s/lib/modules/", helpers_path[ROOTWRAP], helpers_path[RSYNC], modules, tmpdir);
      if (!output) {
      logprintfl (EUCAINFO, "ERROR: failed to rsync the modules\n");
      return ERROR;
      }
      }
    */

    char buf [1024];
    snprintf (buf, sizeof (buf), "default=0\ntimeout=5\n\ntitle TheOS\nroot (hd0,%d)\nkernel /boot/%s root=/dev/sda1 ro\n", part, kfile);
    if (ramdisk) {
        char buf2 [1024];
        snprintf (buf2, sizeof (buf2), "initrd /boot/%s\n", rfile);
        strncat (buf, buf2, sizeof (buf));
    }
    char menu_lst_path [EUCA_MAX_PATH];
    snprintf (menu_lst_path, sizeof (menu_lst_path), "%s/boot/grub/menu.lst", mnt_pt);
    char grub_conf_path [EUCA_MAX_PATH];
    snprintf (grub_conf_path, sizeof (grub_conf_path), "%s/boot/grub/grub.conf", mnt_pt);

    if (diskutil_write2file (menu_lst_path, buf)!=OK)
        return ERROR;
    if (diskutil_write2file (grub_conf_path, buf)!=OK)
        return ERROR;

    return OK;
}

int diskutil_grub_mbr (const char * path, const int part)
{
    char cmd [1024];
    int rc = 1;

    snprintf(cmd, sizeof (cmd), "%s --batch >/dev/null 2>&1", helpers_path[GRUB]);
    logprintfl (EUCADEBUG, "running %s\n", cmd);
    FILE * fp = popen (cmd, "w");
    if (fp!=NULL) {
        char s [EUCA_MAX_PATH];
#define   _PR fprintf (fp, "%s", s); logprintfl (EUCADEBUG, "\t%s", s)
        snprintf (s, sizeof (s), "device (hd0) %s\n", path); _PR;
        snprintf (s, sizeof (s), "root (hd0,%d)\n", part);   _PR;
        snprintf (s, sizeof (s), "setup (hd0)\n");           _PR;
        snprintf (s, sizeof (s), "quit\n");                  _PR;
        rc = pclose (fp);
    }

    if (rc==0) return OK;
    logprintfl (EUCAERROR, "error: failed to run grub on disk '%s': %s\n", path, strerror (errno));
    return ERROR;
}

int diskutil_ch (const char * path, const char * user, const int perms)
{
    int ret = OK;
    char * output;

    if (user) {
        output = pruntf ("%s %s %s %s", helpers_path[ROOTWRAP], helpers_path[CHOWN], user, path);
        if (!output) {
            return ERROR;
        }
        free (output);
    }

    if (perms>0) {
        output = pruntf ("%s %s 0%o %s", helpers_path[ROOTWRAP], helpers_path[CHMOD], perms, path);
        if (!output) {
            return ERROR;
        }
        free (output);
    }

    return OK;
}

int diskutil_mkdir (const char * path)
{
    char * output;

    output = pruntf ("%s %s -p %s", helpers_path[ROOTWRAP], helpers_path[MKDIR], path);
    if (!output) {
        return ERROR;
    }
    free (output);

    return OK;
}

int diskutil_cp (const char * from, const char * to)
{
    char * output;

    output = pruntf ("%s %s %s %s", helpers_path[ROOTWRAP], helpers_path[CP], from, to);
    if (!output) {
        return ERROR;
    }
    free (output);

    return OK;
}

static char * pruntf (char *format, ...)
{
    va_list ap;
    FILE *IF=NULL;
    char cmd[1024], *ptr;
    size_t bytes=0;
    int outsize=1025, rc;
    char *output=NULL;

    va_start(ap, format);
    vsnprintf(cmd, 1024, format, ap);

    strncat(cmd, " 2>&1", 1024);
    output = NULL;

    IF=popen(cmd, "r");
    if (!IF) {
        logprintfl (EUCAERROR, "error: cannot popen() cmd '%s' for read\n", cmd);
        return(NULL);
    }

    output = malloc(sizeof(char) * outsize);
    while((bytes = fread(output+(outsize-1025), 1, 1024, IF)) > 0) {
        output[(outsize-1025)+bytes] = '\0';
        outsize += 1024;
        output = realloc(output, outsize);
    }
    rc = pclose(IF);
    if (rc) {
        logprintfl (EUCADEBUG, "%s\n", output);
        if (output) free(output);
        output = NULL;
        logprintfl (EUCAERROR, "error: bad return code from cmd '%s'\n", cmd);
    }
    return(output);
}

// round up or down to sector size
long long round_up_sec   (long long bytes) { return ((bytes % SECTOR_SIZE) ? (((bytes / SECTOR_SIZE) + 1) * SECTOR_SIZE) : bytes); }
long long round_down_sec (long long bytes) { return ((bytes % SECTOR_SIZE) ? (((bytes / SECTOR_SIZE))     * SECTOR_SIZE) : bytes); }
