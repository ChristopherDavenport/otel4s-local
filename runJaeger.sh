#!/bin/bash

# docker run -d --name otelcollector \
#   -p 1888:1888 \
#   -p 4317:4317 \
#   -p 4318:4318 \
#   otel/opentelemetry-collector:0.77.0
#     # - 1888:1888 # pprof extension
#     # - 8888:8888 # Prometheus metrics exposed by the collector
#     # - 8889:8889 # Prometheus exporter metrics
#     # - 13133:13133 # health_check extension
#     # - 4317:4317 # OTLP gRPC receiver
#     # - 4318:4318 # OTLP http receiver
#     # - 55679:55679 # zpages extension

docker run -d --name jaeger \
  -e COLLECTOR_ZIPKIN_HOST_PORT=:9411 \
  -e COLLECTOR_OTLP_ENABLED=true \
  -p 6831:6831/udp \
  -p 6832:6832/udp \
  -p 5778:5778 \
  -p 16686:16686 \
  -p 4317:4317 \
  -p 4318:4318 \
  -p 14250:14250 \
  -p 14268:14268 \
  -p 14269:14269 \
  -p 9411:9411 \
  jaegertracing/all-in-one:latest