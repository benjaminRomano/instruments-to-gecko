# Instruments To Gecko

Convert an Instruments file into the Gecko Profile Format

## Usage

```
Usage: gecko [OPTIONS]

  Convert Instruments Trace to Gecko Format (Firefox Profiler)

Options:
  -i, --input PATH          Input Instruments Trace
  -a, --arch TEXT           Architecture of device instrumented (arm64e,
                            x86_64)
  --app TEXT                Name of app to match the dSyms to (e.g. YourApp)
  --os-version TEXT         Name of OS Version to use for desymbolicating
                            (e.g. 15.6, 16.1)
  --dsym PATH               Path to DSYM File for app. This can point to .dSYM
                            or symbols directory with an app (e.g.
                            YourApp.app/YourApp)
  --support / --no-support  Whether to de-symbolicate using iOS Device Support libraries
                            (This takes ~1 minute)
  --run INT                 Which run within the trace file to analyze
  -o, --output PATH         Output Path for gecko profile
  -h, --help                Show this message and exit

```

**Example Command**

```bash

# Build executable jar
./gradlew shadowJar

# Convert Instruments trace to Gecko using iOS Device Support libraries for iOS 16.1 on the second run within the trace file
java -jar ./build/libs/instruments-to-gecko.jar --input example.trace --run 2 --dsym YourApp.app/YourApp --app YourApp --support --output examplestandalone.json.gz
```

## Limitations

* Hard-coded assumptions that we want to use iOS Device Support
* Desymbolication is slow (~1min30s for ~800 dSyms) due to usage of `atos` which has slow startup times
  * Possibly, Xctrace's native desymbolication can be used instead as pre-processing step, but more investigation is required on how to pull that out from trace
  required to 
* Only traces from cold app start can be desymbolicated
  * We use dyld kdebug tracepoints to get load addresses and these are only fired during process creation