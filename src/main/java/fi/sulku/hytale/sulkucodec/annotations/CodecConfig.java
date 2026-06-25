package fi.sulku.hytale.sulkucodec.annotations;

import com.hypixel.hytale.codec.schema.metadata.Metadata;
import com.hypixel.hytale.codec.validation.Validator;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicitly configures specific field properties or overrides.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CodecConfig {
    /**
     * Override the default capitalized key mapping name.
     */
    String key() default "";

    /**
     * Specify a custom Codec class if the field type is not natively supported.
     */
    Class<?> customCodec() default Void.class;

    /**
     * Whether this field is required in the configuration.
     * If false, it acts as an optional field (must-have = false).
     */
    boolean required() default true;

    /**
     * Whether to bypass case checking when parsing the configuration key.
     */
    boolean bypassCaseCheck() default false;

    /**
     * Documentation or comment for the configuration field.
     * Natively supported by Hytale's Codec system.
     */
    String comment() default "";

    /**
     * Minimum version this field is applicable for.
     */
    int minVersion() default Integer.MIN_VALUE;

    /**
     * Maximum version this field is applicable for.
     */
    int maxVersion() default Integer.MAX_VALUE;

    /**
     * Whether this field must not be null.
     * If true, applies Hytale's native NonNullValidator.
     */
    boolean nonNull() default false;

    /**
     * Validator classes to apply to this field.
     */
    Class<? extends Validator>[] validators() default {};

    /**
     * Metadata modifiers to apply to this field.
     */
    Class<? extends Metadata>[] metadata() default {};
}