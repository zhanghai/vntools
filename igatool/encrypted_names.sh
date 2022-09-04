#!/bin/bash
set -e
if [[ "$#" -ne 1 ]]; then
    echo "Usage: $0 STEAM_DIRECTORY" >&2
    exit 1
fi
names="$({
    for file in "$1/steamapps/common/Flowers -Le volume sur "*-/*.iga; do
        case "$(basename "$file")" in
            bgimage.iga|fgimage.iga|script.iga|system.iga)
                ./igatool -l "$file"
            ;;
        esac
    done
} | sort | uniq)"
cat >encrypted_names.cpp <<EOF
#include <memory>
#include <string>
#include <unordered_map>

using namespace std;

unordered_map<string, string> CreateEncryptedNames() {
    unordered_map<string, string> names{};
$(echo "$names" | perl -lpe '
use Digest::MD5 qw(md5_hex);
$name = $_;
$md5 = reverse(substr(md5_hex(lc($name)), -12));
$encrypted_name = "";
$index = 0;
for $char (split("", $md5)) {
    $ordinal = ord($char);
    $value = $ordinal + $index + 1;
    if ($ordinal >= ord("0") && $ordinal <= ord("9")) {
        $encrypted_name = $encrypted_name . chr($value % 9 + ord("0"))
    } else {
        $encrypted_name = $encrypted_name . chr($value % 25 + ord("a"));
    }
    ++$index;
}
$_ = "    names.emplace(\"$encrypted_name\", \"$name\");"';
)
    return names;
}

unordered_map<string, string> ENCRYPTED_NAMES = CreateEncryptedNames();
EOF
