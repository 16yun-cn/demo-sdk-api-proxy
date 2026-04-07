# 16YUN(亿牛云) C++ API 提取式示例 Makefile

CXX = g++
CXXFLAGS = -std=c++17 -Wall -Wextra -O2
LIBS = -lcurl
TARGET = main
SOURCE = main.cpp

# 检测操作系统
UNAME_S := $(shell uname -s)

# macOS 特殊处理
ifeq ($(UNAME_S),Darwin)
    # 使用 Homebrew 安装的依赖
    CXXFLAGS += -I/opt/homebrew/include -I/usr/local/include
    LDFLAGS += -L/opt/homebrew/lib -L/usr/local/lib
endif

# Ubuntu/Debian 包管理器提示
UBUNTU_DEPS = libcurl4-openssl-dev nlohmann-json3-dev build-essential
CENTOS_DEPS = libcurl-devel nlohmann-json-devel gcc-c++
MACOS_DEPS = curl nlohmann-json

.PHONY: all clean install-deps help

all: $(TARGET)

$(TARGET): $(SOURCE)
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $(TARGET) $(SOURCE) $(LIBS)

clean:
	rm -f $(TARGET)

install-deps:
	@echo "请根据你的操作系统安装依赖："
	@echo ""
	@echo "Ubuntu/Debian:"
	@echo "  sudo apt-get update"
	@echo "  sudo apt-get install $(UBUNTU_DEPS)"
	@echo ""
	@echo "CentOS/RHEL/Fedora:"
	@echo "  sudo yum install $(CENTOS_DEPS)  # 或使用 dnf"
	@echo ""
	@echo "macOS (使用 Homebrew):"
	@echo "  brew install $(MACOS_DEPS)"
	@echo ""
	@echo "Arch Linux:"
	@echo "  sudo pacman -S curl nlohmann-json base-devel"

help:
	@echo "可用的目标:"
	@echo "  all          - 编译程序"
	@echo "  clean        - 清理编译文件"
	@echo "  install-deps - 显示安装依赖的命令"
	@echo "  help         - 显示此帮助信息"
	@echo ""
	@echo "使用方法:"
	@echo "  1. 安装依赖: make install-deps"
	@echo "  2. 编译程序: make"
	@echo "  3. 运行程序: ./$(TARGET)"
