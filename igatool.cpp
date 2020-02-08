#include <cstdlib>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

/**
 * @see https://github.com/morkt/GARbro/blob/master/ArcFormats/Noesis/ArcIGA.cs
 */

using namespace std;

struct Entry {
    uint32_t name_offset;
    string name;
    uint32_t offset;
    uint32_t size;
};

const static uint32_t BUFFER_SIZE = 4096;

void Usage(const string &program_name) {
    cerr << "Usage: " << program_name << " x IGA_FILE [OUTPUT_DIRECOTRY]\n"
            "Usage: " << program_name << " c IGA_FILE INPUT_FILE...\n";
}

uint32_t ReadPackedUint32(ifstream &iga_file) {
    uint32_t value = 0;
    while ((value & 1) == 0) {
        uint8_t byte;
        iga_file >> byte;
        value = value << 7 | byte;
    }
    return value >> 1;
}

string ReadPackedString(ifstream &iga_file, size_t length) {
    uint8_t *buffer = new uint8_t[length];
    for (size_t i = 0; i < length; ++i) {
        buffer[i] = (uint8_t) ReadPackedUint32(iga_file);
    }
    // This doesn't handle encoding, but we should have ASCII-only names.
    string value{reinterpret_cast<char *>(buffer), length};
    delete[] buffer;
    return value;
}

void Extract(const string &iga_path, const string &output_directory) {
    ifstream iga_file{iga_path, ios::binary};
    iga_file.exceptions(ios::failbit | ios::badbit);

    uint32_t signature;
    iga_file >> signature;
    if (signature != 0x30414749) {
        fprintf(stderr, "Unexpected signature: 0x%08X\n", signature);
        exit(1);
    }

    iga_file.seekg(0, ios::end);
    size_t file_size = iga_file.tellg();

    iga_file.seekg(0x10);
    uint32_t entries_length = ReadPackedUint32(iga_file);
    size_t entries_end = static_cast<size_t>(iga_file.tellg()) + entries_length;
    vector<Entry> entries{};
    while (static_cast<size_t>(iga_file.tellg()) < entries_end) {
        Entry entry{};
        entry.name_offset = ReadPackedUint32(iga_file);
        entry.offset = ReadPackedUint32(iga_file);
        entry.size = ReadPackedUint32(iga_file);
        entries.push_back(entry);
    }

    uint32_t names_length = ReadPackedUint32(iga_file);
    size_t data_start = static_cast<size_t>(iga_file.tellg()) + names_length;
    for (size_t i = 0; i < entries.size(); ++i) {
        Entry &entry = entries[i];
        size_t name_length = (i + 1 < entries.size() ?
                              entries[i + 1].name_offset : names_length)
            - entry.name_offset;
        entry.name = ReadPackedString(iga_file, name_length);
        entry.offset += data_start;

        if (entry.offset + entry.size > file_size) {
            throw overflow_error("Entry offset: " + to_string(entry.offset)
                                 + ", size: " + to_string(entry.size)
                                 + ", file size: " + to_string(file_size));
        }
    }

    uint8_t *buffer = new uint8_t[BUFFER_SIZE];
    for (vector<Entry>::iterator it = entries.begin(); it != entries.end();
         ++it) {
        const Entry &entry = *it;
        iga_file.seekg(entry.offset);
        ofstream output_file{output_directory + entry.name, ios::binary};
        uint32_t size = 0;
        while (size < entry.size) {
            uint32_t transferSize = max(BUFFER_SIZE, entry.size - size);
            iga_file.read(reinterpret_cast<char *>(buffer), transferSize);
            output_file.write(reinterpret_cast<char *>(buffer), transferSize);
            size += transferSize;
        }
        output_file.flush();
    }
    delete[] buffer;
}

void Compress(const string &iga_path, const vector<string> &input_paths) {
    // TODO
    (void)iga_path;
    (void)input_paths;
}

int main(int argc, char *argv[]) {
    if (argc < 2) {
        Usage(argv[1]);
        return 1;
    }
    bool extract;
    string argv1(argv[1]);
    if (argv1 == "x") {
        extract = true;
    } else if (argv1 == "c") {
        extract = false;
    } else {
        Usage(argv[0]);
        return 1;
    }
    if (extract) {
        if (!(argc == 3 || argc == 4)) {
            Usage(argv[0]);
            return 1;
        }
        Extract(argv[2], argc == 4 ? argv[3] : ".");
    } else {
        if (argc < 3) {
            Usage(argv[0]);
            return 1;
        }
        vector<string> input_files{};
        for (int i = 3; i < argc; ++i) {
            input_files.emplace_back(argv[i]);
        }
        Compress(argv[2], input_files);
    }
    return 0;
}
