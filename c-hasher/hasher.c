#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <openssl/sha.h> // for SHA256 functions
#include <sys/stat.h> //gives stat() function to get file size


int main(int argc, char *argv[]) {
    if (argc != 2) {
        fprintf(stderr, "{\"error\": \"Usage: hasher <filepath>\"}\n");
        return 1;
    }

    const char *path = argv[1];
    FILE *fp = fopen(path, "rb");   // "rb" = read binary (critical on Windows)
    if (!fp) {
        fprintf(stdout, "{\"error\": \"Cannot open file: %s\"}\n", path);
        return 1;
    }

    struct stat st;
    stat(path, &st);
    long size_bytes = st.st_size;

    SHA256_CTX ctx;
    SHA256_Init(&ctx);

    unsigned char buffer[65536];   // 64 KB read buffer
    size_t n;
    while ((n = fread(buffer, 1, sizeof(buffer), fp)) > 0) {
        SHA256_Update(&ctx, buffer, n); //incremental function to update the hash with the new data
    }
    fclose(fp);

        unsigned char hash[SHA256_DIGEST_LENGTH];   // 32 bytes
    SHA256_Final(hash, &ctx);

    // Convert 32 binary bytes → 64 hex characters
    char hex[65];
    for (int i = 0; i < SHA256_DIGEST_LENGTH; i++) {
        sprintf(hex + (i * 2), "%02x", hash[i]);
    }
    hex[64] = '\0';

    printf("{\"file\": \"%s\", \"sha256\": \"%s\", \"size_bytes\": %ld}\n", path, hex, size_bytes);
    return 0;
}