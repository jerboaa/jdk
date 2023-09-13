/*
 * Copyright (c) 2008, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.nio.fs;

import jdk.internal.misc.Blocker;

/**
 * Linux specific system calls.
 */

class LinuxNativeDispatcher extends UnixNativeDispatcher {
    private LinuxNativeDispatcher() { }

    // set by JNI code based on glibc support
    private static volatile boolean supports_statx = false;

   /**
    * FILE *setmntent(const char *filename, const char *type);
    */
    static long setmntent(byte[] filename, byte[] type) throws UnixException {
        try (NativeBuffer pathBuffer = NativeBuffers.asNativeBuffer(filename);
             NativeBuffer typeBuffer = NativeBuffers.asNativeBuffer(type)) {
            return setmntent0(pathBuffer.address(), typeBuffer.address());
        }
    }
    private static native long setmntent0(long pathAddress, long typeAddress)
        throws UnixException;

    /**
     * int getmntent(FILE *fp, struct mnttab *mp, int len);
     */

    static int getmntent(long fp, UnixMountEntry entry, int buflen) throws UnixException {
        try (NativeBuffer buffer = NativeBuffers.getNativeBuffer(buflen)) {
            return getmntent0(fp, entry, buffer.address(), buflen);
        }
    }

    static native int getmntent0(long fp, UnixMountEntry entry, long buffer, int bufLen)
        throws UnixException;

    /**
     * int endmntent(FILE* filep);
     */
    static native void endmntent(long stream) throws UnixException;

    /**
     * int posix_fadvise(int fd, off_t offset, off_t len, int advice);
     */
    static native int posix_fadvise(int fd, long offset, long len, int advice)
        throws UnixException;

    static void statx(UnixPath path, LinuxFileAttributes attrs, boolean followLinks)
            throws UnixException {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            long comp = Blocker.begin();
            try {
                int errno = statx0(buffer.address(), attrs, followLinks);
                if (errno != 0) {
                    throw new UnixException(errno);
                }
            } finally {
                Blocker.end(comp);
            }
        }
    }

    static void statxfd(int fd, LinuxFileAttributes attrs)
            throws UnixException {
        long comp = Blocker.begin();
        try {
            int errno = statxfd0(fd, attrs);
            if (errno != 0) {
                throw new UnixException(errno);
            }
        } finally {
            Blocker.end(comp);
        }
    }

    /**
     * int statx(int dirfd, const char *restrict pathname, int flags,
     *           unsigned int mask, struct statx *restrict statxbuf);
     *
     * statx supports lookups with a file descriptor if path name is empty
     * and AT_EMPTY_PATH flag is set in flags. In that case the fd passed in by
     * dirfd will be used.
     */
    static native int statxfd0(int fd, LinuxFileAttributes attrs);

    /**
     * int statx(int dirfd, const char *restrict pathname, int flags,
     *           unsigned int mask, struct statx *restrict statxbuf);
     */
    static native int statx0(long address, LinuxFileAttributes attrs, boolean followLinks);

    /**
     * Copies data between file descriptors {@code src} and {@code dst} using
     * a platform-specific function or system call possibly having kernel
     * support.
     *
     * @param dst destination file descriptor
     * @param src source file descriptor
     * @param addressToPollForCancel address to check for cancellation
     *        (a non-zero value written to this address indicates cancel)
     *
     * @return 0 on success, UNAVAILABLE if the platform function would block,
     *         UNSUPPORTED_CASE if the call does not work with the given
     *         parameters, or UNSUPPORTED if direct copying is not supported
     *         on this platform
     */
    static native int directCopy0(int dst, int src, long addressToPollForCancel)
        throws UnixException;

    static boolean isStatxSupported() {
        return supports_statx;
    }

    // initialize
    private static native void init();

    static {
        jdk.internal.loader.BootLoader.loadLibrary("nio");
        init();
    }
}
