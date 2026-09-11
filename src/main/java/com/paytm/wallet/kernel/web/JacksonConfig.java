package com.paytm.wallet.kernel.web;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Strict typing on the money path.
 *
 * Jackson's defaults are forgiving in ways that are fine for most APIs and
 * wrong for this one. Out of the box it will happily accept {@code
 * "amount_paise": "100"} (a string) or {@code 12.5} (a float) and coerce both
 * into a long - the second by silently truncating, which quietly loses money.
 *
 * The brief is explicit that money is "always integer paise - never floats,
 * never rupees-as-decimal". So a value that is not a JSON integer is a 400,
 * not something to be guessed at. Found by the API coverage suite, which fed
 * the amount as a string and got a 200.
 */
@Configuration
public class JacksonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictIntegerCoercion() {
        return builder -> builder.postConfigurer(mapper ->
                mapper.coercionConfigFor(LogicalType.Integer)
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
