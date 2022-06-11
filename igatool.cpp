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

#define ARRAY_SIZE(array) (sizeof(array) / sizeof(array[0]))

static const uint8_t IGA_SIGNATURE[4] = { 'I', 'G', 'A', '0' };
static const uint8_t IGA_UNKNOWN[4] = { 0x00, 0x00, 0x00, 0x00 };
static const uint8_t IGA_PADDING[8] = { 0x02, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00 };
static const size_t IGA_ENTRIES_OFFSET = sizeof(IGA_SIGNATURE) + sizeof(IGA_UNKNOWN)
        + sizeof(IGA_PADDING);

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
    cerr << "Usage: " << program_name << " -x|-xd IGA_FILE [OUTPUT_DIRECOTRY]\n"
            "Usage: " << program_name << " -c IGA_FILE INPUT_FILE...\n";
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
        if (end) {
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

string ReadLastPackedString(istream &stream, size_t end) {
    auto buffer = make_unique<vector<uint8_t>>();
    while (static_cast<size_t>(stream.tellg()) < end) {
        buffer->push_back(static_cast<uint8_t>(ReadPackedUint32(stream)));
    }
    // This doesn't handle encoding, but we should have ASCII-only names.
    string value{reinterpret_cast<char *>(buffer->data()), buffer->size()};
    return value;
}

void WritePackedString(ostream &stream, const string &value) {
    // This doesn't handle encoding, but we should have ASCII-only names.
    auto buffer = reinterpret_cast<const uint8_t *>(value.c_str());
    for (size_t i = 0; i < value.length(); ++i) {
        WritePackedUint32(stream, static_cast<uint32_t>(buffer[i]));
    }
}

uint8_t GetDataKey(const string &name, bool force_encryption) {
    return force_encryption || string_ends_with(name, ".s") ? 0xFFu : 0;
}

void Extract(const string &iga_path, bool force_decryption, const string &output_directory) {
    ifstream iga_file{iga_path, ios::binary};
    iga_file.exceptions(ios::failbit | ios::badbit);

    auto signature = make_unique<uint8_t[]>(ARRAY_SIZE(IGA_SIGNATURE));
    iga_file.read(reinterpret_cast<char *>(signature.get()), sizeof(IGA_SIGNATURE));
    if (!equal(signature.get(), signature.get() + ARRAY_SIZE(IGA_SIGNATURE), IGA_SIGNATURE)) {
        fprintf(stderr, "Unexpected signature: 0x%02X%02X%02X%02X\n", signature[0], signature[1],
                signature[2], signature[3]);
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
    size_t names_end = static_cast<size_t>(iga_file.tellg()) + names_length;
    for (size_t i = 0; i < entries.size(); ++i) {
        Entry &entry = entries[i];
        if (i < entries.size() - 1) {
            size_t name_length = entries[i + 1].name_offset - entry.name_offset;
            entry.name = ReadPackedString(iga_file, name_length);
        } else {
            // Assuming that entry names are in ASCII, the actual number of bytes used in the file
            // for an entry name should be the same as the difference of name_offset of adjacent
            // entries. However, Shenghuixinglanxueyuan somehow unnecessarily writes one extra 0
            // byte before bytes that have their second-highest bit set to 1 (e.g. lower case
            // letters), but they are still reporting the number of packed uint32s (instead of
            // actual number of bytes used in the file) for name_offset, so that the file pointer
            // will no longer be in sync with name_offset and it broke the simple logic of reading
            // (names_end - name_offset of second last entry） packed uint32s. In this case, we can
            // only read all the packed uint32s until we meet names_end.
            entry.name = ReadLastPackedString(iga_file, names_end);
        }
        entry.offset += names_end;
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
        cout << entry.name << endl;
        ofstream output_file{entry.path, ios::binary};
        output_file.exceptions(ios::failbit | ios::badbit);
        iga_file.seekg(entry.offset);
        uint8_t key = GetDataKey(entry.name, force_decryption);
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
    iga_file.write(reinterpret_cast<const char *>(&IGA_UNKNOWN), sizeof(IGA_UNKNOWN));
    iga_file.write(reinterpret_cast<const char *>(&IGA_PADDING), sizeof(IGA_PADDING));

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
    auto namesString{namesStream.str()};

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
    auto entriesString{entriesStream.str()};

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
        uint8_t key = GetDataKey(entry.name, false);
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
    string argv1{argv[1]};
    bool extract;
    bool force_decryption;
    if (argv1 == "-x") {
        extract = true;
        force_decryption = false;
    } else if (argv1 == "-xd") {
        extract = true;
        force_decryption = true;
    } else if (argv1 == "-c") {
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
        string output_directory = argc == 4 ? argv[3] : ".";
        Extract(argv[2], force_decryption, output_directory);
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
