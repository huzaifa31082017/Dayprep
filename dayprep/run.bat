@echo off
rem Compile and start DayPrep (Windows). Needs JDK 17 or newer.
if not exist out mkdir out
javac -cp lib\sqlite-jdbc.jar -d out backend\*.java && java -cp "out;lib\sqlite-jdbc.jar" Server
