/**
 * XSLT 3.0 transformation with Saxon-HE.
 *
 * <p>One {@code XsltExecutable} is compiled per stylesheet at startup (immutable, thread-safe);
 * a fresh {@code XsltTransformer} is created per document because transformers hold state.
 */
package io.github.katlego95.lexpipeline.transform;
