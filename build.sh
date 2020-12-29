#!/bin/bash
INTERPRETER="target/cish"
mvn clean process-resources package

if [ -f $INTERPRETER ]; then
  rm $INTERPRETER
fi

echo '#!/usr/bin/env java -jar ' >$INTERPRETER
cat interpreter/target/interpreter-0.1-SNAPSHOT-jar-with-dependencies.jar >>$INTERPRETER
chmod +x $INTERPRETER
cp $INTERPRETER docker/build
