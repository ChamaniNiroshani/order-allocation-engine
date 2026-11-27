package com.chamani.order_allocation_engine.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The parts of the OpenAPI description that are not about one endpoint: the API info, the tag order, and the error
 * responses of GlobalExceptionHandler, which the controllers reference by name. Each controller lists the status codes
 * its own endpoints can return; springdoc's guessing from the exception handler is off
 * (springdoc.override-with-generic-response=false), because it would put every error on every endpoint.
 */
@Configuration
public class OpenApiConfig {

    public static final String TAG_ORDERS = "orders";
    public static final String TAG_CHECKOUT = "checkout";
    public static final String TAG_FULFILMENT = "fulfilment";
    public static final String TAG_INVENTORY = "inventory";

    private static final String RESPONSES = "#/components/responses/";
    public static final String BAD_REQUEST = RESPONSES + "BadRequest";
    public static final String NOT_FOUND = RESPONSES + "NotFound";
    public static final String KEY_REUSED = RESPONSES + "IdempotencyKeyReused";
    public static final String UNAVAILABLE = RESPONSES + "ServiceUnavailable";

    private static final String REPOSITORY = "https://github.com/ChamaniNiroshani/order-allocation-engine";
    private static final String ERROR_SCHEMA = "ApiError";

    @Bean
    OpenAPI orderAllocationOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Order Allocation Engine")
                        .version("v1")
                        .description("Allocates orders against limited stock under concurrent requests, with idempotent "
                                + "retries and an audit trail. Source: " + REPOSITORY + "\n\n"
                                + "This is a public demo with no authentication. A real production service would put "
                                + "this documentation, and the API, behind authentication."))
                .externalDocs(new ExternalDocumentation().description("Source code").url(REPOSITORY))
                .tags(List.of(
                        new Tag().name(TAG_ORDERS).description("Direct allocation and order read endpoints"),
                        new Tag().name(TAG_CHECKOUT).description("Checkout saga: reserve, charge, confirm or compensate"),
                        new Tag().name(TAG_FULFILMENT).description("Multi-warehouse fulfilment, with its warehouses and batches"),
                        new Tag().name(TAG_INVENTORY).description("Stock for the orders and checkout flows")))
                .components(new Components()
                        .addSchemas(ERROR_SCHEMA, errorSchema())
                        .addResponses("BadRequest", error("Invalid input: a missing or invalid header, field, path value or body"))
                        .addResponses("NotFound", error("A referenced resource does not exist"))
                        .addResponses("IdempotencyKeyReused",
                                error("The Idempotency-Key was already used with a different request"))
                        .addResponses("ServiceUnavailable", error("Temporarily unavailable: the database cannot be reached "
                                + "or, for POST /api/orders in OPTIMISTIC mode, every lock retry lost. Retry later.")
                                .addHeaderObject("Retry-After", new Header()
                                        .description("Seconds to wait before retrying").schema(new IntegerSchema()))));
    }

    /** The body GlobalExceptionHandler builds. */
    private static Schema<?> errorSchema() {
        return new ObjectSchema()
                .addProperty("timestamp", new StringSchema().format("date-time"))
                .addProperty("status", new IntegerSchema())
                .addProperty("error", new StringSchema())
                .addProperty("message", new StringSchema());
    }

    private static ApiResponse error(String description) {
        return new ApiResponse().description(description).content(new Content().addMediaType(
                org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + ERROR_SCHEMA))));
    }
}
