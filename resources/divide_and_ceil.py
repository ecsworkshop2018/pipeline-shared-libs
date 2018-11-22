import math
import sys


def divide_and_ceil(num, den):
    return math.ceil(float(num) / float(den))


if __name__ == "__main__":
    print(divide_and_ceil(sys.argv[1], sys.argv[2]))