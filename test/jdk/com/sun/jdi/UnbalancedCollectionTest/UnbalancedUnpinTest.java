/*
 * Copyright (c) 2016 Red Hat Inc.
 *
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

/**
 *  @test
 *  @bug 8269232
 *  @summary JDWP: unbalanced unpin should not crash JVM
 *
 *  @library ..
 *  @run build TestScaffold VMConnection TargetListener TargetAdapter
 *  @run compile --patch-module jdk.jdi=${test.src} com/sun/tools/jdi/ObjectReferenceImpl.java
 *  @run compile -g UnbalancedUnpinTest.java
 *  @run main/othervm --patch-module jdk.jdi=${test.class.path} UnbalancedUnpinTest UnbalancedUnpinTestTarget
 */
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.ArrayType;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.InvocationException;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.VMOutOfMemoryException;
import com.sun.jdi.Value;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ExceptionEvent;

/***************** Target program **********************/

class UnbalancedUnpinTestTarget {

    UnbalancedUnpinTestTarget() {
        System.out.println("DEBUG: invoked constructor");
    }

    static class InvokedFromDebugger {
        @SuppressWarnings("unused")
        private byte[] bytes;
        void testMethod() {
	    bytes = new byte[3000000]; // some allocation
            System.out.println("DEBUG: invoked 'void InvokedFromDebugger.testMethod()'");
	    for (int i = 0; i < bytes.length; i++) {
		    bytes[i] = (byte)(i % 255);
	    }
        }
    };

    InvokedFromDebugger clazz = new InvokedFromDebugger();

    public void entry() {}

    public static void main(String[] args){
        System.out.println("DEBUG: UnbalancedUnpinTestTarget.main");
        new UnbalancedUnpinTestTarget().entry();
    }
}

/***************** Test program ************************/

public class UnbalancedUnpinTest extends TestScaffold {

    private ReferenceType targetClass;
    private ObjectReference thisObject;
    private int failedTests;

    public UnbalancedUnpinTest(String[] args) {
        super(args);
        if (args.length != 1) {
            throw new RuntimeException("Test failed unexpectedly.");
        }
    }

    @Override
    protected void runTests() throws Exception {
        try {
            addListener(new TargetAdapter() {

                @Override
                public void exceptionThrown(ExceptionEvent event) {
                    String name = event.exception().referenceType().name();
                    System.err.println("DEBUG: Exception thrown in debuggee was: " + name);
		    failure();
                }
            });
            /*
             * Get to the top of entry()
             * to determine targetClass and mainThread
             */
            BreakpointEvent bpe = startTo("UnbalancedUnpinTestTarget", "entry", "()V");
            targetClass = bpe.location().declaringType();

            mainThread = bpe.thread();

            StackFrame frame = mainThread.frame(0);
            thisObject = frame.thisObject();
	    doRunTest(); // run the actual test
        } catch (SecurityException e) {
            e.printStackTrace();
            failure();
        }
        /*
         * resume the target, listening for events
         */
        listenUntilVMDisconnect();
    }

    private void failure() {
        failedTests++;
    }

    public void determineTestStatus() {
        if (failedTests > 0) {
             throw new RuntimeException("Test failed! See above for details.");
	}
	System.out.println("Test PASSED!");
    }

    /*
     * Main test case.
     */
    @SuppressWarnings("unused") // called via reflection
    private void doRunTest() throws Exception {
        System.out.println("DEBUG: ------------> Running doRunTest");
        try {
            Field field = targetClass.fieldByName("clazz");
            ClassType clsType = (ClassType)field.type();
            Method constructor = getConstructorForClass(clsType);
            for (int i = 0; i < 15; i++) {
                @SuppressWarnings({ "rawtypes", "unchecked" })
                ObjectReference objRef = clsType.newInstance(mainThread,
                                                             constructor,
                                                             new ArrayList(0),
                                                             ObjectReference.INVOKE_NONVIRTUAL);
		try {
		    objRef.disableCollection();
                    invoke(clsType, objRef, "testMethod");
		} finally {
		    objRef.enableCollection();
		    objRef.enableCollection(); // called twice intentionally
		}
            }
        } catch (InvocationException e) {
            defaultHandleFailure(e);
        }
    }

    private Method getConstructorForClass(ClassType clsType) {
        return getMethodByName(clsType, "<init>");
    }

    private Method getMethodByName(ClassType clsType, String name) {
        List<Method> methods = clsType.methodsByName(name);
        if (methods.size() != 1) {
            throw new RuntimeException("FAIL. Expected only one, the default, constructor");
        }
        return methods.get(0);
    }

    private void defaultHandleFailure(Exception e) {
        e.printStackTrace();
        failure();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    void invoke(ClassType clsType, ObjectReference object, String methodName)
            throws Exception {
        List args = new ArrayList();
        invoke(clsType, object, methodName, args);
    }

    void invoke(ClassType clsType, ObjectReference object, String methodName,
                @SuppressWarnings("rawtypes") List args) throws Exception {
        Method method = getMethodByName(clsType, methodName);
        invoke(method, object, args);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    void invoke(Method method, ObjectReference object, List args) throws Exception {
        object.invokeMethod(mainThread, method, args, 0);
        System.out.println("DEBUG: Done invoking method via debugger.");
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("test.vm.opts", "-Xmx20m"); // Set debuggee VM option
        UnbalancedUnpinTest unpinTest = new UnbalancedUnpinTest(args);
        try {
            unpinTest.startTests();
        } catch (Throwable e) {
            System.out.println("DEBUG: Got exception for test run. " + e);
            e.printStackTrace();
            unpinTest.failure();
        }
        unpinTest.determineTestStatus();
    }

}
