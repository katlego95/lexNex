/**
 * HTTP surface: controllers, request/response DTOs, and the RFC 7807 problem+json error model.
 *
 * <p>Both entry points (single document POST, batch POST) funnel into the same per-document
 * pipeline so there is one code path and one set of metrics.
 */
package io.github.katlego95.lexpipeline.api;
