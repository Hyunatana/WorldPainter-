@echo off
setlocal
set "WP_AI_ROOT=%~dp0.."
java -Djava.awt.headless=true -cp "%WP_AI_ROOT%\WorldPainter\WPCore\target\classes;%WP_AI_ROOT%\WorldPainter\WPCore\target\dependency\*" org.pepsoft.worldpainter.ai.WorldGenerationCli %*
exit /b %errorlevel%
