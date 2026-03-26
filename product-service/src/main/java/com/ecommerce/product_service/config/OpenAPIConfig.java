package com.ecommerce.product_service.config;


import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
// To DO : @SecurityScheme para configurar depois.
public class OpenAPIConfig {

    @Bean
    public OpenAPI productServiceAPI(){
        return new OpenAPI()
                .info(new Info()
                        .title("Product-Service API")
                        .description("Product-Service Reference Project")
                        .version("v0.0.1")
                        .license(new License()
                                .name("https://github.com/fabianoqss/ecommerce-microsservice/tree/main/product-service")));
    }
}
