@echo off
rem Builds BTA-Movil.jar and the Amethyst and Zalith packages from your ORIGINAL BTA 8.0.1 bta.jar.
rem Usage: create-modpack.bat ^<bta.jar ^| bta.v8.0.1.mmc.zip ^| folder^> [output_folder] [--install ^<.minecraft^>]
rem Needs only Java 17. Without arguments it opens the installer window.
if "%~1"=="" (
  java -jar "%~dp0BTA-Movil-Installer.jar"
) else (
  java -Djava.awt.headless=true -jar "%~dp0BTA-Movil-Installer.jar" %*
)
