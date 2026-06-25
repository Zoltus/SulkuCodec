package fi.sulku.hytale.sulkucodec.registry;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.metadata.Metadata;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.codec.validation.Validator;
import com.hypixel.hytale.codec.validation.validator.NonNullValidator;
import fi.sulku.hytale.sulkucodec.annotations.CodecConfig;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Supplier;

public final class AutoCodecRegistry {

    private static final Map<Class<?>, Codec<?>> NATIVE_MAPPINGS = new HashMap<>();

    static {
        NATIVE_MAPPINGS.put(boolean.class, Codec.BOOLEAN);
        NATIVE_MAPPINGS.put(Boolean.class, Codec.BOOLEAN);
        NATIVE_MAPPINGS.put(int.class, Codec.INTEGER);
        NATIVE_MAPPINGS.put(Integer.class, Codec.INTEGER);
        NATIVE_MAPPINGS.put(double.class, Codec.DOUBLE);
        NATIVE_MAPPINGS.put(Double.class, Codec.DOUBLE);
        NATIVE_MAPPINGS.put(float.class, Codec.FLOAT);
        NATIVE_MAPPINGS.put(Float.class, Codec.FLOAT);
        NATIVE_MAPPINGS.put(long.class, Codec.LONG);
        NATIVE_MAPPINGS.put(Long.class, Codec.LONG);
        NATIVE_MAPPINGS.put(short.class, Codec.SHORT);
        NATIVE_MAPPINGS.put(Short.class, Codec.SHORT);
        NATIVE_MAPPINGS.put(byte.class, Codec.BYTE);
        NATIVE_MAPPINGS.put(Byte.class, Codec.BYTE);
        NATIVE_MAPPINGS.put(String.class, Codec.STRING);
        NATIVE_MAPPINGS.put(double[].class, Codec.DOUBLE_ARRAY);
        NATIVE_MAPPINGS.put(float[].class, Codec.FLOAT_ARRAY);
        NATIVE_MAPPINGS.put(int[].class, Codec.INT_ARRAY);
        NATIVE_MAPPINGS.put(long[].class, Codec.LONG_ARRAY);
        NATIVE_MAPPINGS.put(byte[].class, Codec.BYTE_ARRAY);
        NATIVE_MAPPINGS.put(String[].class, Codec.STRING_ARRAY);
        NATIVE_MAPPINGS.put(java.nio.file.Path.class, Codec.PATH);
        NATIVE_MAPPINGS.put(java.time.Instant.class, Codec.INSTANT);
        NATIVE_MAPPINGS.put(java.util.logging.Level.class, Codec.LOG_LEVEL);
        NATIVE_MAPPINGS.put(java.time.Duration.class, Codec.DURATION);
        NATIVE_MAPPINGS.put(UUID.class, Codec.UUID_STRING);
    }

    private AutoCodecRegistry() {
    }

    /**
     * Registers a native mapping or overrides an existing mapping.
     */
    public static <T> void registerMapping(Class<T> clazz, Codec<T> codec) {
        NATIVE_MAPPINGS.put(clazz, codec);
    }

    /**
     * Resolves the codec for the given class, generating a BuilderCodec if no native mapping exists.
     */
    @SuppressWarnings("unchecked")
    public static <T> Codec<T> getCodec(Class<T> clazz) {
        Codec<?> codec = NATIVE_MAPPINGS.get(clazz);
        if (codec != null) {
            return (Codec<T>) codec;
        }

        // Check if there is a public static CODEC field
        try {
            Field codecField = clazz.getDeclaredField("CODEC");
            if (Modifier.isStatic(codecField.getModifiers()) && Codec.class.isAssignableFrom(codecField.getType())) {
                codecField.setAccessible(true);
                Codec<T> foundCodec = (Codec<T>) codecField.get(null);
                NATIVE_MAPPINGS.put(clazz, foundCodec);
                return foundCodec;
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // Fall through
        }

        // Dynamically resolve custom array types using Hytale's ArrayCodec
        if (clazz.isArray()) {
            Class<?> componentType = clazz.getComponentType();
            Codec<?> componentCodec = getCodec(componentType);
            Codec<T> arrayCodec = (Codec<T>) new ArrayCodec(componentCodec, size -> Array.newInstance(componentType, size));
            NATIVE_MAPPINGS.put(clazz, arrayCodec);
            return arrayCodec;
        }

        // If no supplier can be resolved easily, we don't automatically generate.
        // Usually, configs are generated using generateCodec(clazz, supplier).
        Codec<T> generatedCodec = generateCodec(clazz, () -> {
            try {
                var constructor = clazz.getDeclaredConstructor();
                constructor.setAccessible(true);
                return constructor.newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Could not instantiate class " + clazz.getName() + " with default constructor", e);
            }
        });
        NATIVE_MAPPINGS.put(clazz, generatedCodec);
        return generatedCodec;
    }

    /**
     * Dynamically generates a BuilderCodec for the given config POJO class using reflection.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> BuilderCodec<T> generateCodec(Class<T> clazz, Supplier<T> instanceSupplier) {
        var builder = BuilderCodec.builder(clazz, instanceSupplier);

        for (Field field : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);

            CodecConfig config = field.getAnnotation(CodecConfig.class);

            // Resolve key name (use custom key name or default to UPPER_SNAKE_CASE/Field Name)
            String codecKey = (config != null && !config.key().isEmpty()) ? config.key() : field.getName();

            // Resolve field codec
            Codec<?> targetCodec;
            if (config != null && config.customCodec() != Void.class) {
                try {
                    targetCodec = (Codec<?>) config.customCodec().getDeclaredConstructor().newInstance();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to instantiate custom codec: " + config.customCodec().getName(), e);
                }
            } else {
                if (List.class.isAssignableFrom(field.getType())) {
                    Class<?> componentType = getAClass(field);
                    targetCodec = getListCodec(componentType);
                } else {
                    targetCodec = getCodec(field.getType());
                }
            }

            // Bind runtime getters and setters dynamically into Hytale's Builder system
            boolean isRequired = config == null || config.required();
            boolean isBypassCaseCheck = config != null && config.bypassCaseCheck();

            var fieldBuilder = builder.append(
                    new KeyedCodec<>(codecKey, (Codec) targetCodec, isRequired, isBypassCaseCheck),
                    (instance, value) -> {
                        try {
                            field.set(instance, value);
                        } catch (IllegalAccessException e) {
                            throw new RuntimeException(e);
                        }
                    },
                    (instance) -> {
                        try {
                            return field.get(instance);
                        } catch (IllegalAccessException e) {
                            throw new RuntimeException(e);
                        }
                    }
            );

            if (config != null) {
                if (config.minVersion() != Integer.MIN_VALUE || config.maxVersion() != Integer.MAX_VALUE) {
                    fieldBuilder.setVersionRange(config.minVersion(), config.maxVersion());
                }

                if (!config.comment().isEmpty()) {
                    fieldBuilder.documentation(config.comment());
                }

                if (config.nonNull()) {
                    fieldBuilder.addValidator(NonNullValidator.INSTANCE);
                }

                for (Class<? extends Validator> validatorClass : config.validators()) {
                    fieldBuilder.addValidator(instantiateClass(validatorClass));
                }

                for (Class<? extends Metadata> metadataClass : config.metadata()) {
                    fieldBuilder.metadata(instantiateClass(metadataClass));
                }
            }

            fieldBuilder.add();
        }

        return builder.build();
    }

    @NonNullDecl
    private static Class<?> getAClass(Field field) {
        Class<?> componentType = Object.class;
        Type genericType = field.getGenericType();
        if (genericType instanceof ParameterizedType pType) {
            Type[] typeArgs = pType.getActualTypeArguments();
            if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                componentType = (Class<?>) typeArgs[0];
            }
        }
        return componentType;
    }

    @SuppressWarnings("unchecked")
    private static <V> V instantiateClass(Class<V> clazz) {
        try {
            try {
                var instanceField = clazz.getDeclaredField("INSTANCE");
                instanceField.setAccessible(true);
                return (V) instanceField.get(null);
            } catch (NoSuchFieldException e) {
                var constructor = clazz.getDeclaredConstructor();
                constructor.setAccessible(true);
                return constructor.newInstance();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate: " + clazz.getName(), e);
        }
    }

    private static <E> Codec<List<E>> getListCodec(Class<E> componentType) {
        Codec<E> elementCodec = getCodec(componentType);
        return new ListCodec<>(elementCodec, componentType);
    }

    public static class ListCodec<E> implements Codec<List<E>> {
        private final ArrayCodec<E> arrayCodec;
        private final Class<E> componentType;

        public ListCodec(Codec<E> elementCodec, Class<E> componentType) {
            this.componentType = componentType;
            this.arrayCodec = new ArrayCodec<>(elementCodec,
                    size -> (E[]) Array.newInstance(componentType, size));
        }

        @Override
        public List<E> decode(org.bson.BsonValue bsonValue, ExtraInfo extraInfo) {
            E[] array = arrayCodec.decode(bsonValue, extraInfo);
            if (array == null) return null;
            return new ArrayList<>(Arrays.asList(array));
        }

        @Override
        public org.bson.BsonValue encode(List<E> list, ExtraInfo extraInfo) {
            if (list == null) return org.bson.BsonNull.VALUE;
            E[] array = list.toArray((E[]) Array.newInstance(componentType, 0));
            return arrayCodec.encode(array, extraInfo);
        }

        @Override
        public List<E> decodeJson(@NonNullDecl RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
            E[] array = arrayCodec.decodeJson(reader, extraInfo);
            if (array == null) return null;
            return new ArrayList<>(Arrays.asList(array));
        }

        @Override
        public Schema toSchema(@NonNullDecl SchemaContext context) {
            return arrayCodec.toSchema(context);
        }
    }
}