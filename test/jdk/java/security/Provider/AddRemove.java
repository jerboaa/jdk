/*
 * Copyright (c) 2021, Red Hat, Inc.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

/*
 * @test
 * @bug 8266929
 * @summary Make sure dynamic addition/removal of providers works correctly
 * @run main/othervm AddRemove false
 * @run main/othervm AddRemove true
 */

import java.security.InvalidKeyException;
import java.security.InvalidParameterException;
import java.security.MessageDigestSpi;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.Security;
import java.security.SignatureException;
import java.security.SignatureSpi;

public class AddRemove {

    public static void main(String[] args) throws Exception {
        boolean doPreCheck = false;
        if (args.length == 1) {
            doPreCheck = Boolean.parseBoolean(args[0]);
        }
        System.out.println("doPreCheck == " + doPreCheck);
        if (doPreCheck) {
             System.out.print("Testing EncryptedPrivateKeyInfo foo provider before it's loaded... ");
             try {
                 new javax.crypto.EncryptedPrivateKeyInfo("FOOBAR", new byte[]{0}); // this should fail
                 throw new RuntimeException("Test failed! Foo provider recognized even though it hasn't been loaded");
             } catch (NoSuchAlgorithmException e) {
                 System.out.println("PASSED.");
             }
        }

        Security.addProvider(new FooProvider());

        System.out.print("Testing EncryptedPrivateKeyInfo foo provider after dyn-add... ");
        try {
            new javax.crypto.EncryptedPrivateKeyInfo("FOOBAR", new byte[]{0}); // this should work
            System.out.println("PASSED.");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Test failed!", e);
        }
        Security.removeProvider("foo");
        System.out.print("Testing EncryptedPrivateKeyInfo foo provider after dyn-remove... ");
        try {
            new javax.crypto.EncryptedPrivateKeyInfo("FOOBAR", new byte[]{0}); // this should fail
            throw new RuntimeException("Test failed! Foo provider recognized even though it has been removed");
        } catch (NoSuchAlgorithmException e) {
            System.out.println("PASSED.");
        }
    }

    public static class FooProvider extends Provider {
        FooProvider() {
            super("foo", "1.0", "none");
            put("FOOBAR", "AddRemove$FooSignatureSpi");
            put("Alg.Alias.Signature.OID.2.16.840.1.101.3.4.3.3", "FOOBAR");
        }
    }

    public static class FooDigest extends MessageDigestSpi {
        public byte[] engineDigest() { return new byte[0]; }
        public void engineReset() {}
        public void engineUpdate(byte input) {}
        public void engineUpdate(byte[] b, int ofs, int len) {}
    }

    public static class FooSignatureSpi extends BaseSignatureSpi {
        public FooSignatureSpi() {
            super();
            System.out.println("FooSignatureSpi constructor");
        }
    }

    public static class BaseSignatureSpi extends SignatureSpi {
        protected void engineInitVerify(PublicKey publicKey) throws InvalidKeyException {
        }
        protected void engineInitSign(PrivateKey privateKey) throws InvalidKeyException {
        }
        protected void engineUpdate(byte b) throws SignatureException { }
        protected void engineUpdate(byte[] b, int off, int len) throws SignatureException { }
        protected byte[] engineSign() throws SignatureException {
            return new byte[0];
        }
        protected boolean engineVerify(byte[] sigBytes) throws SignatureException {
            return false;
        }
        protected void engineSetParameter(String param, Object value) throws InvalidParameterException {
        }
        protected Object engineGetParameter(String param) throws InvalidParameterException {
            return null;
        }
    }

}
