#!/bin/sh
set -eu

data_root="${LAB7_DATA_ROOT:-/var/data}"
document_root="$data_root/www"

mkdir -p "$document_root/uploads"
cp /app/www/index.html "$document_root/index.html"
export LAB7_ROOT="$document_root"

exec java -cp /app/out server.ServerMain
