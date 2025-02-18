#!/bin/bash

set -Eeuo pipefail

help() {
    echo 'Usage: iga2vnmzip FLOWERS_DIRECTORY OUTPUT.vnm.zip' >&2
}

name_to_type() {
    case "$1" in
        bgimage)
            echo background
            ;;
        bgm)
            echo music
            ;;
        fgimage)
            echo foreground
            ;;
        se)
            echo sound
            ;;
        *)
            echo "$1"
            ;;
    esac
}

data_to_name() {
    case "$1" in
        data00)
            echo script
            ;;
        data01)
            echo fgimage
            ;;
        data02)
            echo bgimage
            ;;
        data03)
            echo system
            ;;
        data04)
            echo bgm
            ;;
        *)
            echo "Unknown data $1" >&2
            exit 1
            ;;
    esac
}

main() {
    if [[ $# -ne 2 || ! -d "$1" || "$2" != *.vnm.zip ]]; then
        help
        exit 1
    fi

    if [[ ! (-d "$1/%DEFAULT FOLDER%" || -f "$1/data00.iga") ]]; then
        echo 'Missing unpacked directory or file' >&2
        exit 1
    fi

    temp_dir="$(mktemp -d)"
    trap 'rm -rf -- "$temp_dir"' EXIT

    for f in "$1/"*.iga; do
        if [[ "$f" == *data*.iga ]]; then
            continue
        fi
        echo "Extracting $f..."
        d="$temp_dir/$(name_to_type "$(basename "$f" .iga)")"
        mkdir "$d"
        ../igatool/igatool -x "$f" "$d"
    done
    if [[ -d "$1/%DEFAULT FOLDER%" ]]; then
        for f in "$1/%DEFAULT FOLDER%/"*.iga; do
            echo "Extracting $f..."
            d="$temp_dir/$(name_to_type "$(data_to_name "$(basename "$f" .iga)")")"
            ../igatool/igatool -x "$f" "$d"
        done
    else
        for f in "$1/"data*.iga; do
            echo "Extracting $f..."
            d="$temp_dir/$(name_to_type "$(data_to_name "$(basename "$f" .iga)")")"
            ../igatool/igatool -x "$f" "$d"
        done
    fi

    echo "Copying manifest..."
    cp manifest.yaml "$temp_dir/"

    echo "Copying color backgrounds..."
    cp '#000000.png' "$temp_dir/background/"
    cp '#FFFFFF.png' "$temp_dir/background/"

    echo "Moving avatars..."
    mkdir "$temp_dir/avatar"
    for f in "$temp_dir/foreground/f"*; do
        mv "$f" "$temp_dir/avatar/"
    done

    echo "Converting script to VNMark..."
    kotlin ../igs2vnm/igs2vnm.main.kts "$temp_dir/script" "$temp_dir/vnmark"
    # Original script kept for easier debugging.
    #rm -r "$temp_dir/script"

    for d in "$temp_dir/"*/; do
        for f in "$d"*.bmp; do
            if [[ "$f" == *'*.bmp' ]]; then
                break
            fi
            echo "Converting $f to JPEG..."
            convert -quality 95 "$f" "${f%.bmp}.jpg"
            rm "$f"
        done
    done
    for d in "$temp_dir/"*/; do
        for f in "$d"*.png; do
            if [[ "$f" == *'*.png' ]]; then
                break
            fi
            echo "Converting $f to WEBP..."
            convert -quality 95 "$f" "${f%.png}.webp"
            rm "$f"
        done
    done

    output="$(realpath "$2")"
    echo "Creating $output..."
    rm -f "$output"
    (cd "$temp_dir"; zip -0DrX "$output" .)
}

main "$@"
