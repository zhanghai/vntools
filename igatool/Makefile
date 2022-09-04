CXXFLAGS ?= -O2 -Wall -Wextra -Werror

igatool : igatool.o
	$(CXX) $(LDFLAGS) $^ $(LOADLIBES) $(LDLIBS) -o $@

.INTERMEDIATE : igatool.o
igatool.o : igatool.cpp
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) -c $^ -o $@

.PHONY : clean
clean :
	rm -f igatool
