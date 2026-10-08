# 20 — The disk and the memory

The machine is a `t3.micro`. It has **908 MB of RAM** and an **8 GB disk**. Those are different things. The disk filled up. The database did not do it.

Measured on 8 Oct 2026, on the live box, before the cleanup below.

## What the 8 GB actually is

`lsblk` shows one 8 GB volume, split like this:

| Slice | Size | What it is |
|---|---|---|
| Root `/` | 6.9 GB (about 6.7 GB usable) | Ubuntu, Docker, the swap file, the app |
| `/boot` | 1 GB | The kernel |
| EFI | 106 MB | Boot firmware |
| A tiny partition | 4 MB | Unused |

`df` reports the root filesystem, so it says 6.7 GB, not 8. The other 1.1 GB is the boot slices. When the root was 95% full it still had **351 MB free**, which is the whole remaining room on that 6.7 GB. A later package download pulled that down to **455 MB** before the swap file was shrunk.

## Where those gigabytes went

| What | Size | What it is |
|---|---|---|
| Ubuntu programs (`/usr`) | 2.2 GB | The operating system. It stays. |
| Swap file (`/swapfile`) | 2.0 GB, now 512 MB | Disk used as overflow RAM. See below. |
| Container images | about 1 GB | API 513 MB, Postgres 424 MB, Caddy 93 MB. Three images, no leftovers. |
| Apt download cache | about 300–500 MB | Installers already used, kept on disk. Cleared. |
| Snap packages | about 300 MB | SSM agent and its base. Left in place so the AWS console can still open a session. |

Postgres, the thing that holds the shelf, is **18 MB**.

| Table | Size | Rows | What is in it |
|---|---|---|---|
| `media` | 4.5 MB | 902 | Titles. Synopses are 472 KB. Gallery and character fields are text and URLs (526 KB and 928 KB). |
| `item_neighbor` | 3.0 MB | 36,080 | Which titles sit near which other titles. |
| `source_catalog` | 848 KB | 2,764 | Names of reading sites. Metadata only. |
| `item_vector` | 368 KB | 902 | The recommendation vectors. One short list of numbers per title. |
| Users, ratings, chat, events | under 100 KB each | 9 accounts, 4 chat messages, 34 events | The shelf people actually write. |

Cover search stores one 64-bit number per cover (`cover_hash`), for 901 covers. The picture stays on the source site. Chapter pages are fetched when someone opens them and are not written to this disk. Moving that 18 MB to Firebase would not free the volume. Sign-in already uses Firebase. The shelf, the scores, and the catalog stay in Postgres.

## What a swap file is

RAM is the memory the CPU uses while a program runs. This box has under a gigabyte of it. A swap file is a reserved stretch of the **disk** that the kernel can use when RAM is tight: it copies idle memory out to the file and copies it back when the program needs it. That is slower than RAM, and it spends disk.

On 7 Oct, during setup, the file was created by hand:

```text
fallocate -l 2G /swapfile
mkswap /swapfile
swapon /swapfile
```

A line in `/etc/fstab` turns it on at boot. It was 2 GB because the RAM is about 1 GB and the setup gave it twice that much overflow. The AWS hibernation helper did **not** create it. At first boot that helper read a config that asks for 4 GB (`target-size-mb = 4000`) and then exited, because this instance was not launched with hibernation. The config is now 512 MB, so a later run of that helper cannot aim at 4 GB on this disk.

At the moment we looked, **275 MB** of the swap was in use while **417 MB** of RAM was still available. The kernel's default `swappiness` is 60, so it parks idle pages on disk even when RAM has room. That is why a 2 GB file felt necessary. The programs themselves were small:

| Process | Memory |
|---|---|
| API (Java) | 107 MB, inside a 384 MB heap cap |
| Postgres | 36 MB, with a setting that *told the planner* the machine had 4 GB of cache |
| Caddy | 17 MB |
| Docker and the OS | the rest |

The 384 MB heap cap plus a 160 MB metaspace cap is a ceiling, not the current use. If the API ever grew to that ceiling, plus Postgres and Ubuntu, the 908 MB of RAM would spill into swap. The 2 GB file was the safety net for that ceiling. It was also a quarter of the whole disk.

## What we changed

| Change | Before | After |
|---|---|---|
| Swap file | 2.0 GB | 512 MB |
| `vm.swappiness` | 60 | 10, saved in `/etc/sysctl.d/99-aniga-mem.conf` |
| Java | `-Xmx384m`, metaspace 160 MB | `-Xmx256m`, metaspace 96 MB |
| Container caps | none | API 400 MB, Postgres 192 MB, Caddy 128 MB |
| Postgres `shared_buffers` | 128 MB | 32 MB |
| Postgres `effective_cache_size` | 4 GB | 256 MB |
| Postgres `max_connections` | 100 | 30 |
| Apt | kept every downloaded package | cleaned, and set to autoclean daily |

The caps live in `infra/docker-compose.ec2.yml`. Recreating the containers applied them. The Postgres volume was not removed. Health on the private network came back `UP`, with 902 titles and 2,764 catalog sources. The APK URL on port 80 still returned 200.

After this, the root disk was **4.1 GB used, 2.6 GB free, 62%**. Swap settled at about **64 MB used** of the 512 MB. During the kernel-header removal the machine briefly used 214 MB of that 512 MB, which is the overflow doing its job, and then it climbed back down.

Kernel header packages and a few unused tracing tools were removed (about 250 MB). The running kernel image stayed. The `linux-aws` metapackage went with the headers, because that metapackage depends on them. The next kernel update will need that metapackage installed again, and it will bring the headers back with it.

## What still will not fit

A local chat model wants more RAM than this instance has, and more disk than we should give one file. The disk has room for another API image now (the image is 513 MB and 2.6 GB is free). The RAM does not have room for a model beside Postgres and the API. Search by title, by story, and by picture stays as it is.

The three container images are most of the remaining Docker disk. The next image swap should still remove the previous `aniga-backend` image once the new one is running, so two copies do not sit side by side longer than the deploy.
