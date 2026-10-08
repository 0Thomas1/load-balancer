package com.example.lb.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.File;
import java.io.IOException;

/**
 * Serves the built frontend ({@code frontend/dist}) when it exists.
 *
 * <p>In dev the Vite server on 5173 is used instead (it proxies {@code /api}
 * and {@code /ws} to this backend), so this config is a no-op while
 * {@code frontend/dist} is absent. When present, a single {@code /**} handler
 * serves the build output and the SPA root forwards to {@code index.html} so
 * client-side routes keep working after a rebuild.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final File distDir;

    public WebConfig(@Value("${app.frontend-dist:../frontend/dist}") String distPath) {
        this.distDir = new File(distPath);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!distDir.isDirectory()) {
            return; // dev mode: Vite serves the frontend with /api and /ws proxies
        }
        String location = distDir.toURI().toString();
        if (!location.endsWith("/")) {
            location += "/"; // keep createRelative() inside dist/
        }

        registry.addResourceHandler("/**")
                .addResourceLocations(location)
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    /**
     * Forwards the SPA root to {@code index.html}. Only registered while the
     * built frontend exists, so dev mode keeps its previous behavior.
     */
    @Controller
    @Conditional(DistDirExistsCondition.class)
    static class SpaRootController {
        @GetMapping("/")
        public String index() {
            return "forward:/index.html";
        }
    }

    /** True when {@code app.frontend-dist} resolves to an existing directory. */
    public static class DistDirExistsCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String path = context.getEnvironment().getProperty("app.frontend-dist", "../frontend/dist");
            return new File(path).isDirectory();
        }
    }

    /**
     * Serves {@code index.html} whenever the requested resource does not exist
     * or is a directory, handing unknown routes to the SPA.
     */
    private static final class SpaFallbackResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = location.createRelative(resourcePath);
            if (requested.exists() && requested.isReadable() && requested.isFile()) {
                return requested;
            }
            Resource index = location.createRelative("index.html");
            return index.exists() && index.isReadable() ? index : null;
        }
    }
}