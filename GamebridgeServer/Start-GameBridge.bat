@echo off
color 0A
title Gamebridge PC Server
cd /d "%~dp0"
echo Verificando e instalando requerimientos si faltan...
pip install -r requirements.txt -q
echo Listo. Iniciando el servidor...
start http://localhost:8080
python server.py
pause
