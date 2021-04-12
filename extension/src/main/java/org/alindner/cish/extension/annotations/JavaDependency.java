package org.alindner.cish.extension.annotations;

import org.alindner.cish.extension.Type;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;


@Retention(RetentionPolicy.RUNTIME)
@Repeatable(JavaDependencies.class)
public @interface JavaDependency {
	String value();
	String version() default "latest";
	Type type() default Type.EQUALS;
}
