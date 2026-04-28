# Contributing

This is a personal project, but pull requests are welcome.

## Guidelines

1. Fork the repo and create a branch from `main`
2. Describe what you changed and why in the PR
3. Make sure the build passes before submitting
4. One PR per logical change

## What's especially useful

- Audio routing fixes for BLE/BT devices
- Video pipeline performance improvements
- Telegram bot improvements
- Support for new glasses models

## Project structure

```
samples/CameraAccessAndroid/   # Android app
samples/CameraAccess/server/   # Node.js server
docker/                        # Docker deployment
```

## Local development

Android: Android Studio, see `samples/CameraAccessAndroid/README.md`  
Server: `node samples/CameraAccess/server/index.js`

## Issues

Bugs and suggestions go through GitHub Issues. Please include: what you expected, what happened, and steps to reproduce.
