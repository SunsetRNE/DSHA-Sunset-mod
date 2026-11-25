/* Synthetic guest process. It checks each loader can read a file that only
 * exists inside the virtual root; no Android or DSHA user data is involved. */
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(void) {
    char text[32] = {0};
    FILE *input = fopen("/proof.txt", "r");
    if (!input || !fgets(text, sizeof(text), input) || fclose(input)) return 3;
    if (strcmp(text, "dsha-local-test\n") || getuid() != 0) return 4;
    printf("DSHA_PROOT_GUEST abi=%zu uid=%u proof=ok\n", 8 * sizeof(void *), (unsigned)getuid());
    return 0;
}
