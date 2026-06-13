package com.wpanther.transcript.signing.infrastructure.adapter.in.camel;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CommandValidator implements Processor {

    private final Validator validator;

    @Override
    public void process(Exchange exchange) {
        Object command = exchange.getIn().getBody();
        if (command == null) {
            throw new IllegalArgumentException("Command body is null");
        }
        Set<ConstraintViolation<Object>> violations = validator.validate(command);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException("Command validation failed: " + message);
        }
    }
}
