# Instruments To Gecko

Convert an Instruments file into the Gecko Profile Format

**Note: XCode 14.3 Beta or higher is required**

## Usage

```
Usage: gecko [OPTIONS]

  Convert Instruments Trace to Gecko Format (Firefox Profiler)

Options:
  -i, --input PATH          Input Instruments Trace
  --app TEXT                Name of app to match the dSyms to (e.g. YourApp)
  --run INT                 Which run within the trace file to analyze
  -o, --output PATH         Output Path for gecko profile
  -h, --help                Show this message and exit

```

**Example Command**

```bash

# Build executable jar
./gradlew shadowJar

# Convert Instruments trace to Gecko on the second run within the trace file
java -jar ./build/libs/instruments-to-gecko.jar --input example.trace --run 2 --app YourApp --output examplestandalone.json.gz
```