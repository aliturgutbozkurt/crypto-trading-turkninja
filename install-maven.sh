#!/bin/bash
# Quick Maven installer script

cd /tmp
curl -L -o maven.tar.gz "https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.tar.gz"
tar -xzf maven.tar.gz
echo "Maven installed to /tmp/apache-maven-3.9.6"
echo "Run: export PATH=/tmp/apache-maven-3.9.6/bin:\$PATH"
