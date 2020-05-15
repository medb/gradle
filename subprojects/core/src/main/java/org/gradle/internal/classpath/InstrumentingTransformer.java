/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.classpath;

import org.codehaus.groovy.runtime.callsite.CallSiteArray;
import org.gradle.api.file.RelativePath;
import org.gradle.internal.Pair;
import org.gradle.internal.hash.Hasher;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Properties;

class InstrumentingTransformer implements CachedClasspathTransformer.Transform {
    private static final Type SYSTEM_TYPE = Type.getType(System.class);
    private static final Type STRING_TYPE = Type.getType(String.class);
    private static final Type INSTRUMENTED_TYPE = Type.getType(Instrumented.class);
    private static final Type INTEGER_TYPE = Type.getType(Integer.class);
    private static final Type LONG_TYPE = Type.getType(Long.class);
    private static final Type BOOLEAN_TYPE = Type.getType(Boolean.class);

    private static final String RETURN_STRING_FROM_STRING = Type.getMethodDescriptor(STRING_TYPE, STRING_TYPE);
    private static final String RETURN_STRING_FROM_STRING_STRING = Type.getMethodDescriptor(STRING_TYPE, STRING_TYPE, STRING_TYPE);
    private static final String RETURN_STRING_FROM_STRING_STRING_STRING = Type.getMethodDescriptor(STRING_TYPE, STRING_TYPE, STRING_TYPE, STRING_TYPE);
    private static final String RETURN_INTEGER_FROM_STRING = Type.getMethodDescriptor(INTEGER_TYPE, STRING_TYPE);
    private static final String RETURN_INTEGER_FROM_STRING_INT = Type.getMethodDescriptor(INTEGER_TYPE, STRING_TYPE, Type.INT_TYPE);
    private static final String RETURN_INTEGER_FROM_STRING_INTEGER = Type.getMethodDescriptor(INTEGER_TYPE, STRING_TYPE, INTEGER_TYPE);
    private static final String RETURN_INTEGER_FROM_STRING_STRING = Type.getMethodDescriptor(INTEGER_TYPE, STRING_TYPE, STRING_TYPE);
    private static final String RETURN_INTEGER_FROM_STRING_INT_STRING = Type.getMethodDescriptor(INTEGER_TYPE, STRING_TYPE, Type.INT_TYPE, STRING_TYPE);
    private static final String RETURN_INTEGER_FROM_STRING_INTEGER_STRING = Type.getMethodDescriptor(INTEGER_TYPE, STRING_TYPE, INTEGER_TYPE, STRING_TYPE);
    private static final String RETURN_LONG_FROM_STRING = Type.getMethodDescriptor(LONG_TYPE, STRING_TYPE);
    private static final String RETURN_LONG_FROM_STRING_PRIMITIVE_LONG = Type.getMethodDescriptor(LONG_TYPE, STRING_TYPE, Type.LONG_TYPE);
    private static final String RETURN_LONG_FROM_STRING_LONG = Type.getMethodDescriptor(LONG_TYPE, STRING_TYPE, LONG_TYPE);
    private static final String RETURN_LONG_FROM_STRING_STRING = Type.getMethodDescriptor(LONG_TYPE, STRING_TYPE, STRING_TYPE);
    private static final String RETURN_LONG_FROM_STRING_PRIMITIVE_LONG_STRING = Type.getMethodDescriptor(LONG_TYPE, STRING_TYPE, Type.LONG_TYPE, STRING_TYPE);
    private static final String RETURN_LONG_FROM_STRING_LONG_STRING = Type.getMethodDescriptor(LONG_TYPE, STRING_TYPE, LONG_TYPE, STRING_TYPE);
    private static final String RETURN_PRIMITIVE_BOOLEAN_FROM_STRING = Type.getMethodDescriptor(Type.BOOLEAN_TYPE, STRING_TYPE);
    private static final String RETURN_PRIMITIVE_BOOLEAN_FROM_STRING_STRING = Type.getMethodDescriptor(Type.BOOLEAN_TYPE, STRING_TYPE, STRING_TYPE);
    private static final String RETURN_PROPERTIES = Type.getMethodDescriptor(Type.getType(Properties.class));
    private static final String RETURN_PROPERTIES_FROM_STRING = Type.getMethodDescriptor(Type.getType(Properties.class), STRING_TYPE);
    private static final String RETURN_CALL_SITE_ARRAY = Type.getMethodDescriptor(Type.getType(CallSiteArray.class));
    private static final String RETURN_VOID_FROM_CALL_SITE_ARRAY = Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(CallSiteArray.class));

    private static final String INSTRUMENTED_CALL_SITE_METHOD = "$instrumentedCallSiteArray";
    private static final String CREATE_CALL_SITE_ARRAY_METHOD = "$createCallSiteArray";

    private static final String[] NO_EXCEPTIONS = new String[0];

    @Override
    public void applyConfigurationTo(Hasher hasher) {
        hasher.putString(InstrumentingTransformer.class.getSimpleName());
        hasher.putInt(6); // decoration format, increment this when making changes
    }

    @Override
    public Pair<RelativePath, ClassVisitor> apply(ClasspathEntryVisitor.Entry entry, ClassVisitor visitor) {
        return Pair.of(entry.getPath(), new InstrumentingVisitor(visitor));
    }

    private static class InstrumentingVisitor extends ClassVisitor {
        private String className;
        private boolean hasCallSites;

        public InstrumentingVisitor(ClassVisitor visitor) {
            super(Opcodes.ASM7, visitor);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            super.visit(version, access, name, signature, superName, interfaces);
            this.className = name;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (name.equals(CREATE_CALL_SITE_ARRAY_METHOD) && descriptor.equals(RETURN_CALL_SITE_ARRAY)) {
                hasCallSites = true;
            }
            return new InstrumentingMethodVisitor(className, methodVisitor);
        }

        @Override
        public void visitEnd() {
            if (hasCallSites) {
                MethodVisitor methodVisitor = super.visitMethod(Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_PRIVATE, INSTRUMENTED_CALL_SITE_METHOD, RETURN_CALL_SITE_ARRAY, null, NO_EXCEPTIONS);
                methodVisitor.visitCode();
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, className, CREATE_CALL_SITE_ARRAY_METHOD, RETURN_CALL_SITE_ARRAY, false);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, INSTRUMENTED_TYPE.getInternalName(), "groovyCallSites", RETURN_VOID_FROM_CALL_SITE_ARRAY, false);
                methodVisitor.visitInsn(Opcodes.ARETURN);
                methodVisitor.visitMaxs(2, 0);
                methodVisitor.visitEnd();
            }
            super.visitEnd();
        }
    }

    private static class InstrumentingMethodVisitor extends MethodVisitor {
        private final String className;

        public InstrumentingMethodVisitor(String className, MethodVisitor methodVisitor) {
            super(Opcodes.ASM7, methodVisitor);
            this.className = className;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            // TODO - load the class literal instead of class name to pass to the methods on Instrumented
            if (opcode == Opcodes.INVOKESTATIC) {
                if (owner.equals(SYSTEM_TYPE.getInternalName())) {
                    if (name.equals("getProperty")) {
                        if (descriptor.equals(RETURN_STRING_FROM_STRING)) {
                            visitLdcInsn(Type.getObjectType(className).getClassName());
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, INSTRUMENTED_TYPE.getInternalName(), "systemProperty", RETURN_STRING_FROM_STRING_STRING, false);
                            return;
                        }
                        if (descriptor.equals(RETURN_STRING_FROM_STRING_STRING)) {
                            visitLdcInsn(Type.getObjectType(className).getClassName());
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, INSTRUMENTED_TYPE.getInternalName(), "systemProperty", RETURN_STRING_FROM_STRING_STRING_STRING, false);
                            return;
                        }
                    } else if (name.equals("getProperties") && descriptor.equals(RETURN_PROPERTIES)) {
                        visitLdcInsn(Type.getObjectType(className).getClassName());
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, INSTRUMENTED_TYPE.getInternalName(), "systemProperties", RETURN_PROPERTIES_FROM_STRING, false);
                        return;
                    }
                } else if (owner.equals(INTEGER_TYPE.getInternalName()) && name.equals("getInteger")) {
                    if (descriptor.equals(RETURN_INTEGER_FROM_STRING)) {
                        visitLdcInsn(Type.getObjectType(className).getClassName());
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, INSTRUMENTED_TYPE.getInternalName(), "getInteger", RETURN_INTEGER_FROM_STRING_STRING, false);
                        return;
                    }
                    if (descriptor.equals(RETURN_INTEGER_FROM_STRING_INT)) {
                        visitLdcInsn(Type.getObjectType(className).getClassName());
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, INSTRUMENTED_TYPE.getInternalName(), "getInteger", RETURN_INTEGER_FROM_STRING_INT_STRING, false);
                        return;
                    }
                    if (descriptor.equals(RETURN_INTEGER_FROM_STRING_INTEGER)) {
                        visitLdcInsn(Type.getObjectType(className).getClassName());
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, INSTRUMENTED_TYPE.getInternalName(), "getInteger", RETURN_INTEGER_FROM_STRING_INTEGER_STRING, false);
                        return;
                    }
                } else if (owner.equals(LONG_TYPE.getInternalName()) && name.equals("getLong")) {
                    if (descriptor.equals(RETURN_LONG_FROM_STRING)) {
                        visitLdcInsn(Type.getObjectType(className).getClassName());
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, INSTRUMENTED_TYPE.getInternalName(), "getLong", RETURN_LONG_FROM_STRING_STRING, false);
                        return;
                    }
                    if (descriptor.equals(RETURN_LONG_FROM_STRING_PRIMITIVE_LONG)) {
                        visitLdcInsn(Type.getObjectType(className).getClassName());
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, INSTRUMENTED_TYPE.getInternalName(), "getLong", RETURN_LONG_FROM_STRING_PRIMITIVE_LONG_STRING, false);
                        return;
                    }
                    if (descriptor.equals(RETURN_LONG_FROM_STRING_LONG)) {
                        visitLdcInsn(Type.getObjectType(className).getClassName());
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, INSTRUMENTED_TYPE.getInternalName(), "getLong", RETURN_LONG_FROM_STRING_LONG_STRING, false);
                        return;
                    }
                } else if (owner.equals(BOOLEAN_TYPE.getInternalName()) && name.equals("getBoolean") && descriptor.equals(RETURN_PRIMITIVE_BOOLEAN_FROM_STRING)) {
                    visitLdcInsn(Type.getObjectType(className).getClassName());
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, INSTRUMENTED_TYPE.getInternalName(), "getBoolean", RETURN_PRIMITIVE_BOOLEAN_FROM_STRING_STRING, false);
                    return;
                } else if (owner.equals(className) && name.equals(CREATE_CALL_SITE_ARRAY_METHOD) && descriptor.equals(RETURN_CALL_SITE_ARRAY)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, className, INSTRUMENTED_CALL_SITE_METHOD, RETURN_CALL_SITE_ARRAY, false);
                    return;
                }
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }
}
