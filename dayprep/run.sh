#!/bin/sh
# Compile and start DayPrep (Mac / Linux). Needs JDK 17 or newer.
mkdir -p out
javac -cp lib/sqlite-jdbc.jar -d out backend/*.java && java -cp "out:lib/sqlite-jdbc.jar" Server
