# Demo App Cloning

## Requirements

Complete [Getting Started](getting-started.md) first.

## 1. Create A Custom App

On macOS/Linux:

```bash
./app new
```

On Windows:

```bat
app.bat new
```

The command prompts for:

- app name
- template app

The template list comes from app modules in `apps-config.json`.
`basicAssembler` and `basicSubscriberPython` are shown first as recommended
templates.

Non-interactive examples:

On macOS/Linux:

```bash
./app new --name myRlApp --template basicAssembler
./app new --name myPythonApp --template basicSubscriberPython
```

On Windows:

```bat
app.bat new --name myRlApp --template basicAssembler
app.bat new --name myPythonApp --template basicSubscriberPython
```

The CLI copies the template into `apps/<appName>`, rewrites package and
namespace references, updates `apps-config.json`, and stores the local target in
`.app.local.properties`. That local target file is ignored by Git.

## 2. Select A Target App

Show the current target:

On macOS/Linux:

```bash
./app target
```

On Windows:

```bat
app.bat target
```

Choose from a list:

On macOS/Linux:

```bash
./app target --select
```

On Windows:

```bat
app.bat target --select
```

Set a target directly:

On macOS/Linux:

```bash
./app target myRlApp
```

On Windows:

```bat
app.bat target myRlApp
```

Run `./app --help` to see build, install, launch, run, and log commands.
On Windows, use `app.bat --help`.
