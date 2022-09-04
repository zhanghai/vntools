# igatool

Tool for extracting and compressing `.iga` files.

## Build

```bash
mkdir build
cd build
cmake ..
make
```

## Usage

```bash
igatool -x|-xs IGA_FILE [OUTPUT_DIRECOTRY]
igatool -c IGA_FILE INPUT_FILE...
```

## Shenghuixinglanxueyuan

Shenghuixinglanxueyuan packed their `.iga` files into their executable with [Enigma Virtual Box](https://enigmaprotector.com/en/aboutvb.html). Once unpacked, their `.iga` files can be extracted as usual. `-xs` can be used for decrypting their script files.
