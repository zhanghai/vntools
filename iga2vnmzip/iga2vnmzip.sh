#!/bin/bash

set -Eeuo pipefail

help() {
    echo 'Usage: iga2vnmzip FLOWERS_DIRECTORY OUTPUT.vnm.zip|OUTPUT_DIRECTORY/' >&2
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
        system)
            echo template
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
    if [[ $# -ne 2 || ! -d "$1" || ! ( "$2" == */ || "$2" == *.vnm.zip ) ]]; then
        help
        exit 1
    fi

    if [[ ! (-d "$1/%DEFAULT FOLDER%" || -f "$1/data00.iga") ]]; then
        echo 'Missing unpacked directory or file' >&2
        exit 1
    fi

    [[ "$2" == *.vnm.zip ]] && is_zip=true || is_zip=false

    local output_dir
    if [[ "$is_zip" == true ]]; then
        output_dir="$(mktemp -d)"
        trap 'rm -rf -- "$output_dir"' EXIT
    else
        output_dir="${2%/}"
        mkdir -p "$output_dir"
    fi

    for f in "$1/"*.iga; do
        if [[ "$f" == *data*.iga ]]; then
            continue
        fi
        echo "Extracting $f..."
        d="$output_dir/$(name_to_type "$(basename "$f" .iga)")"
        mkdir "$d"
        ../igatool/igatool -x "$f" "$d"
    done
    if [[ -d "$1/%DEFAULT FOLDER%" ]]; then
        for f in "$1/%DEFAULT FOLDER%/"*.iga; do
            echo "Extracting $f..."
            d="$output_dir/$(name_to_type "$(data_to_name "$(basename "$f" .iga)")")"
            ../igatool/igatool -x "$f" "$d"
        done
    else
        for f in "$1/"data*.iga; do
            echo "Extracting $f..."
            d="$output_dir/$(name_to_type "$(data_to_name "$(basename "$f" .iga)")")"
            ../igatool/igatool -x "$f" "$d"
        done
    fi

    echo "Lowercasing files..."
    for d in "$output_dir/"*/; do
        for f in "$d"*; do
            if [[ "$f" == *'*' ]]; then
                break
            fi
            local f2
            f2="${f,,}"
            if [[ "$f" != "$f2" ]]; then
                mv "$f" "$f2"
            fi
        done
    done

    echo "Copying manifest..."
    cp manifest.yaml "$output_dir/"

    echo "Copying template..."
    cp index.html "$output_dir/template/"

    echo "Copying color backgrounds..."
    cp 'black.png' "$output_dir/background/"
    cp 'white.png' "$output_dir/background/"

    echo "Copying credits vnmark..."
    mkdir -p "$output_dir/vnmark"
    for f in *_99999.vnm; do
        cp "$f" "$output_dir/vnmark/"
    done

    echo "Moving avatars..."
    mkdir -p "$output_dir/avatar"
    for f in "$output_dir/foreground/f"*; do
        mv "$f" "$output_dir/avatar/"
    done

    echo "Moving template sounds..."
    for f in "$output_dir/sound/sys_"*; do
        mv "$f" "$output_dir/template/"
    done

    echo "Moving credit images..."
    for f in "$output_dir/template/credit"*; do
        mv "$f" "$output_dir/foreground/"
    done

    echo "Converting script to VNMark..."
    kotlin ../igs2vnm/igs2vnm.main.kts "$output_dir/script" "$output_dir/vnmark"
    rm -r "$output_dir/script"

    for d in "$output_dir/"*/; do
        for f in "$d"*.bmp; do
            if [[ "$f" == *'*.bmp' ]]; then
                break
            fi
            echo "Converting $f to JPEG..."
            convert -quality 95 "$f" "${f%.bmp}.jpg"
            rm "$f"
        done
    done
    for d in "$output_dir/"*/; do
        for f in "$d"*.png; do
            if [[ "$f" == *'*.png' ]]; then
                break
            fi
            echo "Converting $f to WEBP..."
            convert -quality 95 "$f" "${f%.png}.webp"
            rm "$f"
        done
    done
    for f in "$output_dir/video/"*.mpg; do
        if [[ "$f" == *'*.mpg' ]]; then
            break
        fi
        echo "Converting $f to MP4..."
        ffmpeg -i "$f" -c:v libx264 -preset slow -tune animation -crf 18 -c:a aac -b:a 128k "${f%.mpg}.mp4"
        rm "$f"
    done

    echo "Creating file list..."
    find "$output_dir" -type f -printf '%P\n' | sort >"$output_dir/files.lst"

    if [[ "$is_zip" == true ]]; then
        output="$(realpath "$2")"
        echo "Creating $output..."
        rm -f "$output"
        (cd "$output_dir"; zip -0DrX "$output" .)
    fi
}

main "$@"
