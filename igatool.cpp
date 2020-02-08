#include <cstdlib>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <sstream>
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
    string path;
};

#ifdef _WIN32
#define SEPARATOR '\\'
#else
#define SEPARATOR '/'
#endif

#define BUFFER_SIZE 4096u

static const uint32_t IGA_SIGNATURE = 0x30414749;
static const size_t IGA_ENTRIES_OFFSET = 0x10;

bool string_ends_with(const string &str, const string& suffix) {
    return str.size() >= suffix.size()
           && str.compare(str.size()-suffix.size(), suffix.size(), suffix) == 0;
}

string GetFileName(const string &path) {
    size_t last_separator_index = path.find_last_of(SEPARATOR);
    if (last_separator_index == path.size() - 1) {
        throw invalid_argument(path);
    } else if (last_separator_index != string::npos) {
        return path.substr(last_separator_index + 1);
    } else {
        return path;
    }
}

void Usage(const string &program_name) {
    cerr << "Usage: " << program_name << " x IGA_FILE [OUTPUT_DIRECOTRY]\n"
            "Usage: " << program_name << " c IGA_FILE INPUT_FILE...\n";
}

uint32_t ReadPackedUint32(istream &stream) {
    uint32_t value = 0;
    while ((value & 1u) == 0) {
        uint8_t byte;
        stream.read(reinterpret_cast<char *>(&byte), sizeof(byte));
        value = value << 7u | byte;
    }
    return value >> 1u;
}

bool WritePackedUint32Byte(ostream &stream, uint8_t byte, bool started, bool end) {
    byte &= 0b01111111u;
    started |= byte != 0;
    if (started | end) {
        byte <<= 1u;
        if (!end) {
            byte |= 0b00000001u;
        }
        stream.write(reinterpret_cast<const char *>(&byte), sizeof(byte));
    }
    return started;
}

void WritePackedUint32(ostream &stream, uint32_t value) {
    bool started = false;
    started |= WritePackedUint32Byte(stream, value >> 28u, started, false);
    started |= WritePackedUint32Byte(stream, value >> 21u, started, false);
    started |= WritePackedUint32Byte(stream, value >> 14u, started, false);
    started |= WritePackedUint32Byte(stream, value >> 7u, started, false);
    WritePackedUint32Byte(stream, value, started, true);
}

string ReadPackedString(istream &stream, size_t length) {
    auto buffer = make_unique<uint8_t[]>(length);
    for (size_t i = 0; i < length; ++i) {
        buffer[i] = static_cast<uint8_t>(ReadPackedUint32(stream));
    }
    // This doesn't handle encoding, but we should have ASCII-only names.
    string value{reinterpret_cast<char *>(buffer.get()), length};
    return value;
}

void WritePackedString(ostream &stream, const string &value) {
    // This doesn't handle encoding, but we should have ASCII-only names.
    auto buffer = reinterpret_cast<const uint8_t *>(value.c_str());
    for (size_t i = 0; i < value.length(); ++i) {
        WritePackedUint32(stream, static_cast<uint32_t>(buffer[i]));
    }
}

uint8_t GetDataKey(const string &name) {
    return string_ends_with(name, ".s") ? 0xFFu : 0;
}

void Extract(const string &iga_path, const string &output_directory) {
    ifstream iga_file{iga_path, ios::binary};
    iga_file.exceptions(ios::failbit | ios::badbit);

    uint32_t signature;
    iga_file.read(reinterpret_cast<char *>(&signature), sizeof(signature));
    if (signature != IGA_SIGNATURE) {
        fprintf(stderr, "Unexpected signature: 0x%08X\n", signature);
        exit(1);
    }

    iga_file.seekg(0, ios::end);
    size_t file_size = iga_file.tellg();

    iga_file.seekg(IGA_ENTRIES_OFFSET);
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
        size_t name_length = (i + 1 < entries.size() ? entries[i + 1].name_offset : names_length)
                             - entry.name_offset;
        entry.name = ReadPackedString(iga_file, name_length);

        entry.offset += data_start;
        entry.path = output_directory + SEPARATOR + entry.name;

        if (entry.offset + entry.size > file_size) {
            throw out_of_range("Entry offset: " + to_string(entry.offset) + ", size: "
                               + to_string(entry.size) + ", file size: " + to_string(file_size));
        }
    }

    static_assert(BUFFER_SIZE % (UINT8_MAX + 1) == 0,
            "BUFFER_SIZE must be a multiple of (UINT8_MAX + 1) for decryption to work");
    auto buffer = make_unique<uint8_t[]>(BUFFER_SIZE);
    for (const auto &entry : entries) {
        ofstream output_file{entry.path, ios::binary};
        output_file.exceptions(ios::failbit | ios::badbit);
        iga_file.seekg(entry.offset);
        uint8_t key = GetDataKey(entry.name);
        uint32_t size = 0;
        while (size < entry.size) {
            uint32_t transferSize = min(BUFFER_SIZE, entry.size - size);
            iga_file.read(reinterpret_cast<char *>(buffer.get()), transferSize);
            for (size_t i = 0; i < transferSize; ++i) {
                buffer[i] ^= static_cast<uint8_t>((i + 2) ^ key);
            }
            output_file.write(reinterpret_cast<char *>(buffer.get()), transferSize);
            size += transferSize;
        }
        output_file.flush();
    }
}

void Compress(const string &iga_path, const vector<string> &input_paths) {
    ofstream iga_file{iga_path, ios::binary};
    iga_file.exceptions(ios::failbit | ios::badbit);

    iga_file.write(reinterpret_cast<const char *>(&IGA_SIGNATURE), sizeof(IGA_SIGNATURE));

    vector<Entry> entries{};
    for (const auto &input_path : input_paths) {
        Entry entry{};
        entry.path = input_path;
        entries.push_back(entry);
    }

    stringstream namesStream{ios::out};
    uint32_t name_offset = 0;
    namesStream.exceptions(ios::failbit | ios::badbit);
    for (auto &entry : entries) {
        entry.name_offset = name_offset;
        entry.name = GetFileName(entry.path);
        WritePackedString(namesStream, entry.name);
        name_offset += entry.name.length();
    }
    auto namesString = namesStream.str();

    uint32_t offset = 0;
    for (auto &entry : entries) {
        entry.offset = offset;
        ifstream input_file{entry.path, ios::binary};
        input_file.exceptions(ios::failbit | ios::badbit);
        input_file.seekg(0, ios::end);
        entry.size = input_file.tellg();
        offset += entry.size;
    }

    stringstream entriesStream{ios::out};
    entriesStream.exceptions(ios::failbit | ios::badbit);
    for (auto &entry : entries) {
        WritePackedUint32(entriesStream, entry.name_offset);
        WritePackedUint32(entriesStream, entry.offset);
        WritePackedUint32(entriesStream, entry.size);
    }
    auto entriesString = entriesStream.str();

    iga_file.seekp(IGA_ENTRIES_OFFSET);
    uint32_t entriesLength = entriesString.length();
    WritePackedUint32(iga_file, entriesLength);
    iga_file.write(entriesString.c_str(), entriesLength);

    uint32_t namesLength = namesString.length();
    WritePackedUint32(iga_file, namesLength);
    iga_file.write(namesString.c_str(), namesLength);

    static_assert(BUFFER_SIZE % (UINT8_MAX + 1) == 0,
                  "BUFFER_SIZE must be a multiple of (UINT8_MAX + 1) for encryption to work");
    auto buffer = make_unique<uint8_t[]>(BUFFER_SIZE);
    for (auto &entry : entries) {
        ifstream input_file{entry.path, ios::binary};
        input_file.exceptions(ios::failbit | ios::badbit);
        uint8_t key = GetDataKey(entry.name);
        uint32_t size = 0;
        while (size < entry.size) {
            uint32_t transferSize = min(BUFFER_SIZE, entry.size - size);
            input_file.read(reinterpret_cast<char *>(buffer.get()), transferSize);
            for (size_t i = 0; i < transferSize; ++i) {
                buffer[i] ^= static_cast<uint8_t>((i + 2) ^ key);
            }
            iga_file.write(reinterpret_cast<char *>(buffer.get()), transferSize);
            size += transferSize;
        }
    }
    iga_file.flush();
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
