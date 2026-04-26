#!/bin/bash
gradle clean jar
pushd demo
gradle clean build
echo
echo "CLILogger Demo output below:"
echo
java -jar build/libs/demo.jar
echo
echo "Demo finished."
popd
