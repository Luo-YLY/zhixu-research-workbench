#!/bin/sh
set -eu

# Docker Compose file secrets are mounted only inside the app and database containers.
# Keep the password out of the image, Compose file, browser, and host command line.
DB_PASSWORD=$(cat /run/secrets/db_password)
export DB_PASSWORD
exec java -jar /opt/workbench/app.jar
