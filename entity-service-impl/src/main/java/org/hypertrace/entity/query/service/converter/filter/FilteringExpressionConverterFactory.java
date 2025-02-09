package org.hypertrace.entity.query.service.converter.filter;

import org.hypertrace.entity.query.service.converter.ConversionException;
import org.hypertrace.entity.query.service.v1.Value;

public interface FilteringExpressionConverterFactory {
  FilteringExpressionConverter getConverter(final Value value) throws ConversionException;
}
