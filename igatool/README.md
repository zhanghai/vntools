# igatool

Tool for extracting and compressing `.iga` files from Innocent Grey (mainly for Flowers series).

## Build

```bash
mkdir build
cd build
cmake ..
make
```

Or simply:

```bash
make
```

## Usage

```bash
igatool -l IGA_FILE
igatool -x IGA_FILE [OUTPUT_DIRECOTRY]
igatool -c IGA_FILE INPUT_FILE...
```

## Shenghuixinglanxueyuan

Shenghuixinglanxueyuan packed their `.iga` files into their executable with [Enigma Virtual Box](https://enigmaprotector.com/en/aboutvb.html). Once unpacked, their `.iga` files can be extracted as usual, and this tool will handle their file name and script encryption automatically.
