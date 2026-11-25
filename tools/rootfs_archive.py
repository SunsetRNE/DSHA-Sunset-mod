"""Read the signed rootfs's gzip or single CRC64 XZ stream with bounded decoding."""
from contextlib import contextmanager
import gzip
import io
import lzma
import tarfile


XZ_MAGIC = b'\xfd7zXZ\x00'
MEMORY_LIMIT = 8 * 1024 * 1024
TAR_LIMIT = 512 * 1024 * 1024
CHUNK = 64 * 1024


class RootfsInput(io.RawIOBase):
    def __init__(self, source):
        self.source = source
        self.decoded_bytes = 0
        self.gzip = None
        self.xz = None
        magic = source.peek(len(XZ_MAGIC))
        if magic.startswith(XZ_MAGIC):
            self.xz = lzma.LZMADecompressor(format=lzma.FORMAT_XZ, memlimit=MEMORY_LIMIT)
        elif magic.startswith(b'\x1f\x8b'):
            self.gzip = gzip.GzipFile(fileobj=source)
        else:
            raise ValueError('ROOTFS_ARCHIVE_FORMAT')

    def readable(self):
        return True

    def readinto(self, buffer):
        limit = min(len(buffer), TAR_LIMIT - self.decoded_bytes + 1)
        if not limit:
            return 0
        if self.xz is None:
            data = self.gzip.read(limit)
        else:
            data = b''
            while not data and not self.xz.eof:
                chunk = self.source.read(CHUNK) if self.xz.needs_input else b''
                if self.xz.needs_input and not chunk:
                    raise EOFError('ROOTFS_XZ_TRUNCATED')
                data = self.xz.decompress(chunk, max_length=limit)
                if self.xz.eof:
                    if self.xz.check != lzma.CHECK_CRC64:
                        raise ValueError('ROOTFS_XZ_CHECK')
                    if self.xz.unused_data or self.source.read(1):
                        raise ValueError('ROOTFS_XZ_TRAILING_DATA')
        if len(data) > TAR_LIMIT - self.decoded_bytes:
            raise ValueError('ROOTFS_TAR_LIMIT')
        buffer[:len(data)] = data
        self.decoded_bytes += len(data)
        return len(data)

    def close(self):
        if self.gzip is not None:
            self.gzip.close()
        super().close()


@contextmanager
def open_rootfs_tar(stream):
    with io.BufferedReader(stream, CHUNK) as raw:
        with io.BufferedReader(RootfsInput(raw), CHUNK) as decoded:
            with tarfile.open(fileobj=decoded, mode='r|') as archive:
                yield archive
            # tarfile finishes at the tar end marker; still verify the codec's footer.
            while decoded.read(CHUNK):
                pass
