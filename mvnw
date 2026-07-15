#!/usr/bin/env bash
# Project wrapper — forces Java 21 for Maven so Lombok annotation processing works.
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
exec mvn "$@"
