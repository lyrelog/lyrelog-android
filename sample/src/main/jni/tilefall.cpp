// Tilefall's board in C++, with a bug to show a native crash: a tile that isn't on the board is
// read through a null pointer, inside an inlined call.
#include <jni.h>

namespace tilefall {

struct Tile {
    int value;
    Tile* next;
};

class Board {
public:
    explicit Board(Tile* first) : first_(first) {}

    static inline int weight(const Tile* t) { return t->value * 3; }

    __attribute__((noinline)) int score(const Tile* t) const {
        int total = weight(t);
        for (Tile* c = first_; c != nullptr; c = c->next) total += c->value;
        return total;
    }

private:
    Tile* first_;
};

__attribute__((noinline)) int drop(const Tile* missing) {
    Tile a{2, nullptr};
    Board board(&a);
    return board.score(missing);
}

} // namespace tilefall

extern "C" JNIEXPORT jint JNICALL Java_com_example_tilefall_NativeBoard_drop(JNIEnv*, jclass) {
    volatile const tilefall::Tile* missing = nullptr;
    return tilefall::drop(const_cast<const tilefall::Tile*>(missing));
}
