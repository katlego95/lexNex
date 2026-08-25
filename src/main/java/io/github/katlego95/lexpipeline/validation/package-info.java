/**
 * XSD validation, the trust gate.
 *
 * <p>Uses the JDK {@code javax.xml.validation} (Xerces) stack, not Saxon: XSD validation is a
 * Saxon-EE feature. One immutable {@code Schema} is compiled at startup; a {@code Validator}
 * is created per document because validators are not thread-safe.
 */
package io.github.katlego95.lexpipeline.validation;
