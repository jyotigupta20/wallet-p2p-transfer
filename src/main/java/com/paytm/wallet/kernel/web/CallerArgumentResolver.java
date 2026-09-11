package com.paytm.wallet.kernel.web;

import com.paytm.wallet.auth.AuthService;
import com.paytm.wallet.auth.Caller;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets a controller declare {@code Caller caller} and receive the authenticated
 * user. Resolution failures throw {@link ApiException}, so they travel the same
 * problem+json path as every other error rather than a filter writing its own
 * ad-hoc response body.
 */
@Component
public class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String BEARER = "Bearer ";

    private final AuthService auth;

    public CallerArgumentResolver(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Caller.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mav,
                                  NativeWebRequest request,
                                  WebDataBinderFactory binderFactory) {
        return auth.resolve(bearerToken(request));
    }

    /** Extracts the raw token, or null when absent/not a Bearer scheme. */
    public static String bearerToken(NativeWebRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
