CXXFLAGS ?= -O2 -Wall -Wextra -Werror

OS := $(patsubst %.cpp,%.o,$(wildcard *.cpp))

igatool : $(OS)
	$(CXX) $(LDFLAGS) $^ $(LOADLIBES) $(LDLIBS) -o $@

.INTERMEDIATE : $(OS)
%.o : %.cpp
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) -c $^ -o $@

.PHONY : clean
clean :
	rm -f igatool
