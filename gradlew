#!/bin/sh
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -f "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" ]; then
  exec java -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
fi
echo "Gradle Wrapper JAR is missing."
exit 1
