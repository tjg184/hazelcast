/*
 * Copyright (c) 2008-2012, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.nio.serialization;

import com.hazelcast.core.ManagedContext;
import com.hazelcast.instance.OutOfMemoryErrorDispatcher;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class SerializationServiceImpl implements SerializationService {

    private static final int OUTPUT_STREAM_BUFFER_SIZE = 100 << 10;

    private final ConcurrentMap<Class, TypeSerializer> typeMap = new ConcurrentHashMap<Class, TypeSerializer>();
    private final ConcurrentMap<Integer, TypeSerializer> idMap = new ConcurrentHashMap<Integer, TypeSerializer>();
    private final AtomicReference<TypeSerializer> fallback = new AtomicReference<TypeSerializer>();
    private final Queue<ContextAwareDataOutput> outputPool = new ConcurrentLinkedQueue<ContextAwareDataOutput>();
    private final DataSerializer dataSerializer;
    private final PortableSerializer portableSerializer;
    private final ManagedContext managedContext;
    final SerializationContext serializationContext;

    public SerializationServiceImpl(PortableFactory portableFactory) {
        this(portableFactory, null);
    }

    public SerializationServiceImpl(PortableFactory portableFactory, ManagedContext managedContext) {
        this.managedContext = managedContext;
        serializationContext = new SerializationContextImpl(portableFactory, 5);
        safeRegister(DataSerializable.class, dataSerializer = new DataSerializer());
        safeRegister(Portable.class, portableSerializer = new PortableSerializer(serializationContext));
        safeRegister(String.class, new DefaultSerializers.StringSerializer());
        safeRegister(Long.class, new DefaultSerializers.LongSerializer());
        safeRegister(Integer.class, new DefaultSerializers.IntegerSerializer());
        safeRegister(byte[].class, new DefaultSerializers.ByteArraySerializer());
        safeRegister(Boolean.class, new DefaultSerializers.BooleanSerializer());
        safeRegister(Date.class, new DefaultSerializers.DateSerializer());
        safeRegister(BigInteger.class, new DefaultSerializers.BigIntegerSerializer());
        safeRegister(Externalizable.class, new DefaultSerializers.Externalizer());
        safeRegister(Serializable.class, new DefaultSerializers.ObjectSerializer());
        safeRegister(Class.class, new DefaultSerializers.ClassSerializer());
    }

    @SuppressWarnings("unchecked")
    public Data toData(final Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Data) {
            return (Data) obj;
        }
        final ContextAwareDataOutput out = pop();
        try {
            final TypeSerializer serializer = serializerFor(obj.getClass());
            if (serializer == null) {
                throw new NotSerializableException("There is no suitable serializer for " + obj.getClass());
            }
            serializer.write(out, obj);
            final Data data = new Data(serializer.getTypeId(), out.toByteArray());
            if (serializer instanceof PortableSerializer) {
                data.cd = ((PortableSerializer) serializer).getClassDefinition((Portable) obj);
            }
//            if (obj instanceof PartitionAware) {
//                final Data partitionKey = writeObject(((PartitionAware) obj).getPartitionKey());
//                final int partitionHash = (partitionKey == null) ? -1 : partitionKey.getPartitionHash();
//                data.setPartitionHash(partitionHash);
            return data;
        } catch (Throwable e) {
            if (e instanceof OutOfMemoryError) {
                OutOfMemoryErrorDispatcher.onOutOfMemory((OutOfMemoryError) e);
            }
            if (e instanceof HazelcastSerializationException) {
                throw (HazelcastSerializationException) e;
            }
            throw new HazelcastSerializationException(e);
        } finally {
            push(out);
        }
    }

    private ContextAwareDataOutput pop() {
        ContextAwareDataOutput out = outputPool.poll();
        if (out == null) {
            out = new ContextAwareDataOutput(OUTPUT_STREAM_BUFFER_SIZE, this);
        }
        return out;
    }

    void push(ContextAwareDataOutput out) {
        out.reset();
        outputPool.offer(out);
    }

    public Object toObject(final Data data) {
        if ((data == null) || (data.buffer == null) || (data.buffer.length == 0)) {
            return null;
        }
        ContextAwareDataInput in = null;
        try {
            final int typeId = data.type;
            final TypeSerializer serializer = serializerFor(typeId);
            if (serializer == null) {
                throw new IllegalArgumentException("There is no suitable de-serializer for type " + typeId);
            }
            in = new ContextAwareDataInput(data, this);
            Object obj = serializer.read(in);
            if (managedContext != null) {
                managedContext.initialize(obj);
            }
            return obj;
        } catch (Throwable e) {
            if (e instanceof OutOfMemoryError) {
                OutOfMemoryErrorDispatcher.onOutOfMemory((OutOfMemoryError) e);
            }
            if (e instanceof HazelcastSerializationException) {
                throw (HazelcastSerializationException) e;
            }
            throw new HazelcastSerializationException(e);
        } finally {
            if (in != null) {
                in.close();
            }
        }
    }

    public void writeObject(final ObjectDataOutput out, final Object obj) {
        if (obj == null) {
            throw new NullPointerException("Object is required!");
        }
        try {
            final TypeSerializer serializer = serializerFor(obj.getClass());
            if (serializer == null) {
                throw new NotSerializableException("There is no suitable serializer for " + obj.getClass());
            }
            out.writeInt(serializer.getTypeId());
            serializer.write(out, obj);
        } catch (Throwable e) {
            if (e instanceof OutOfMemoryError) {
                OutOfMemoryErrorDispatcher.onOutOfMemory((OutOfMemoryError) e);
            }
            if (e instanceof HazelcastSerializationException) {
                throw (HazelcastSerializationException) e;
            }
            throw new HazelcastSerializationException(e);
        }
    }

    public Object readObject(final ObjectDataInput in) {
        try {
            int typeId = in.readInt();
            final TypeSerializer serializer = serializerFor(typeId);
            if (serializer == null) {
                throw new IllegalArgumentException("There is no suitable de-serializer for type " + typeId);
            }
            Object obj = serializer.read(in);
            if (managedContext != null) {
                managedContext.initialize(obj);
            }
            return obj;
        } catch (Throwable e) {
            if (e instanceof OutOfMemoryError) {
                OutOfMemoryErrorDispatcher.onOutOfMemory((OutOfMemoryError) e);
            }
            if (e instanceof HazelcastSerializationException) {
                throw (HazelcastSerializationException) e;
            }
            throw new HazelcastSerializationException(e);
        }
    }

    public ObjectDataInput createObjectDataInput(byte[] data) {
        return new ContextAwareDataInput(data, this);
    }

    public ObjectDataOutput createObjectDataOutput(int size) {
        return new ContextAwareDataOutput(size, this);
    }

    public void register(final TypeSerializer serializer, final Class type) {
        if (type == null) {
            throw new IllegalArgumentException("Class type information is required!");
        }
        if (serializer.getTypeId() < 0) {
            throw new IllegalArgumentException("Type id must be positive! Current: " + serializer.getTypeId());
        }
        safeRegister(type, serializer);
    }

    public void registerFallback(final TypeSerializer serializer) {
        if (!fallback.compareAndSet(null, serializer)) {
            throw new IllegalStateException("Fallback serializer is already registered!");
        }
    }

    public void deregister(final Class type) {
        final TypeSerializer factory = typeMap.remove(type);
        if (factory != null) {
            idMap.remove(factory.getTypeId());
        }
    }

    public void deregisterFallback() {
        fallback.set(null);
    }

    public TypeSerializer serializerFor(final Class type) {
        TypeSerializer serializer = null;
        if (DataSerializable.class.isAssignableFrom(type)) {
            serializer = dataSerializer;
        } else if (Portable.class.isAssignableFrom(type)) {
            serializer = portableSerializer;
        } else {
            serializer = typeMap.get(type);
            if (serializer == null) {
                // look for super classes
                Class typeSuperclass = type.getSuperclass();
                List<Class> interfaces = new LinkedList<Class>();
                Collections.addAll(interfaces, type.getInterfaces());
                while (typeSuperclass != null) {
                    if ((serializer = registerFromSuperType(type, typeSuperclass)) != null) {
                        break;
                    }
                    Collections.addAll(interfaces, typeSuperclass.getInterfaces());
                    typeSuperclass = typeSuperclass.getSuperclass();
                }
                if (serializer == null) {
                    // look for interfaces
                    for (Class typeInterface : interfaces) {
                        if ((serializer = registerFromSuperType(type, typeInterface)) != null) {
                            break;
                        }
                    }
                }
                if (serializer == null && (serializer = fallback.get()) != null) {
                    safeRegister(type, serializer);
                }
            }
        }
        return serializer;
    }

    private TypeSerializer registerFromSuperType(final Class type, final Class superType) {
        final TypeSerializer serializer = typeMap.get(superType);
        if (serializer != null) {
            safeRegister(type, serializer);
        }
        return serializer;
    }

    private void safeRegister(final Class type, final TypeSerializer serializer) {
        if (DataSerializable.class.isAssignableFrom(type)
                && serializer.getClass() != DataSerializer.class) {
            throw new IllegalArgumentException("Internal DataSerializable[" + type + "] " +
                    "serializer cannot be overridden!");
        }
        if (Portable.class.isAssignableFrom(type)
                && serializer.getClass() != PortableSerializer.class) {
            throw new IllegalArgumentException("Internal DataSerializable[" + type + "] " +
                    "serializer cannot be overridden!");
        }
        TypeSerializer f = typeMap.putIfAbsent(type, serializer);
        if (f != null && f.getClass() != serializer.getClass()) {
            throw new IllegalStateException("Serializer[" + f + "] has been already registered for type: " + type);
        }
        f = idMap.putIfAbsent(serializer.getTypeId(), serializer);
        if (f != null && f.getClass() != serializer.getClass()) {
            throw new IllegalStateException("Serializer [" + f + "] has been already registered for type-id: "
                    + serializer.getTypeId());
        }
    }

    public TypeSerializer serializerFor(final int typeId) {
        return idMap.get(typeId);
    }

    public int poolSize() {
        return outputPool.size();
    }

    public SerializationContext getSerializationContext() {
        return serializationContext;
    }

    public void destroy() {
        for (TypeSerializer serializer : typeMap.values()) {
            serializer.destroy();
        }
        typeMap.clear();
        idMap.clear();
        fallback.set(null);
        for (ContextAwareDataOutput output : outputPool) {
            output.close();
        }
        outputPool.clear();
    }

    private class SerializationContextImpl implements SerializationContext {

        final PortableFactory portableFactory;

        final int version;

        final ConcurrentMap<Long, ClassDefinitionImpl> versionedDefinitions = new ConcurrentHashMap<Long,
                ClassDefinitionImpl>();

        private SerializationContextImpl(PortableFactory portableFactory, int version) {
            this.portableFactory = portableFactory;
            this.version = version;
        }

        public ClassDefinitionImpl lookup(int classId) {
            return versionedDefinitions.get(combineToLong(classId, version));
        }

        public ClassDefinitionImpl lookup(int classId, int version) {
            return versionedDefinitions.get(combineToLong(classId, version));
        }

        public Portable createPortable(int classId) {
            return portableFactory.create(classId);
        }

        public ClassDefinitionImpl createClassDefinition(final byte[] compressedBinary) throws IOException {
            final ContextAwareDataOutput out = pop();
            final byte[] binary;
            try {
                decompress(compressedBinary, out);
                binary = out.toByteArray();
            } finally {
                push(out);
            }
            ClassDefinitionImpl cd = new ClassDefinitionImpl();
            cd.readData(new ContextAwareDataInput(binary, SerializationServiceImpl.this));
            cd.setBinary(binary);
            versionedDefinitions.putIfAbsent(combineToLong(cd.classId, cd.version), cd);
            registerNestedDefinitions(cd);
            return cd;
        }

        private void registerNestedDefinitions(ClassDefinitionImpl cd) throws IOException {
            Collection<ClassDefinition> nestedDefinitions = cd.getNestedClassDefinitions();
            for (ClassDefinition classDefinition : nestedDefinitions) {
                final long key = combineToLong(classDefinition.getClassId(), classDefinition.getVersion());
                final ClassDefinitionImpl nestedCD = (ClassDefinitionImpl) classDefinition;
                registerClassDefinition(key, nestedCD);
                registerNestedDefinitions(nestedCD);
            }
        }

        public void registerClassDefinition(int classId, ClassDefinitionImpl cd) throws IOException {
            registerClassDefinition(combineToLong(classId, version), cd);
        }

        public void registerClassDefinition(long versionedClassId, ClassDefinitionImpl cd) throws IOException {
            if (versionedDefinitions.putIfAbsent(versionedClassId, cd) == null) {
                if (cd.getBinary() == null) {
                    final ContextAwareDataOutput out = pop();
                    try {
                        cd.writeData(out);
                        final byte[] binary = out.toByteArray();
                        out.reset();
                        compress(binary, out);
                        cd.setBinary(out.toByteArray());
                    } finally {
                        push(out);
                    }
                }
            }
        }

        private void compress(byte[] input, OutputStream out) throws IOException {
            Deflater deflater = new Deflater();
            deflater.setLevel(Deflater.BEST_COMPRESSION);
            deflater.setInput(input);
            deflater.finish();
            byte[] buf = new byte[input.length / 10];
            while (!deflater.finished()) {
                int count = deflater.deflate(buf);
                out.write(buf, 0, count);
            }
            deflater.end();
        }

        private void decompress(byte[] compressedData, OutputStream out) throws IOException {
            Inflater inflater = new Inflater();
            inflater.setInput(compressedData);
            byte[] buf = new byte[1024];
            while (!inflater.finished()) {
                try {
                    int count = inflater.inflate(buf);
                    out.write(buf, 0, count);
                } catch (DataFormatException e) {
                    throw new IOException(e.getClass().getName() + ": " + e.getMessage());
                }
            }
            inflater.end();
        }

        public int getVersion() {
            return version;
        }
    }

    private static long combineToLong(int x, int y) {
        return ((long) x << 32) | ((long) y & 0xFFFFFFFL);
    }

    private static int extractInt(long value, boolean lowerBits) {
        return (lowerBits) ? (int) value : (int) (value >> 32);
    }
}