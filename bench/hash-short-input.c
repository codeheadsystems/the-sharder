/*
 * Short-input throughput of SipHash-2-4, MurmurHash3 x64_128, and xxHash64.
 *
 * Measures nanoseconds per hash evaluation at the four framed input lengths HASH-030 produces at a
 * 16-octet routing key and a 10-octet node identity.  The figures in
 * docs/design/adr/0001-hash-function-and-key-encoding.md come from this source.
 *
 *   cc -O2 -o hash-short-input hash-short-input.c && ./hash-short-input
 *
 * The SipHash-2-4 implementation checks itself against the 15-octet row of the reference table
 * before any timing runs.  The three implementations are reference constructions, present to be
 * compared against each other, and none of them is the library's.
 */

#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

/* ---- SipHash-2-4, reference construction ---- */
#define ROTL(x,b) (uint64_t)(((x) << (b)) | ((x) >> (64 - (b))))
#define SIPROUND do { \
  v0 += v1; v1 = ROTL(v1,13); v1 ^= v0; v0 = ROTL(v0,32); \
  v2 += v3; v3 = ROTL(v3,16); v3 ^= v2; \
  v0 += v3; v3 = ROTL(v3,21); v3 ^= v0; \
  v2 += v1; v1 = ROTL(v1,17); v1 ^= v2; v2 = ROTL(v2,32); } while(0)
static uint64_t siphash24(const uint8_t *in, size_t inlen, const uint8_t *k) {
  uint64_t k0, k1; memcpy(&k0,k,8); memcpy(&k1,k+8,8);
  uint64_t v0=0x736f6d6570736575ULL^k0, v1=0x646f72616e646f6dULL^k1,
           v2=0x6c7967656e657261ULL^k0, v3=0x7465646279746573ULL^k1;
  const uint8_t *end = in + inlen - (inlen % 8);
  uint64_t b = ((uint64_t)inlen) << 56, m;
  for (; in != end; in += 8) { memcpy(&m,in,8); v3 ^= m; SIPROUND; SIPROUND; v0 ^= m; }
  switch (inlen & 7) {
    case 7: b |= ((uint64_t)in[6]) << 48; /* fallthrough */
    case 6: b |= ((uint64_t)in[5]) << 40; /* fallthrough */
    case 5: b |= ((uint64_t)in[4]) << 32; /* fallthrough */
    case 4: b |= ((uint64_t)in[3]) << 24; /* fallthrough */
    case 3: b |= ((uint64_t)in[2]) << 16; /* fallthrough */
    case 2: b |= ((uint64_t)in[1]) << 8;  /* fallthrough */
    case 1: b |= ((uint64_t)in[0]); break;
    case 0: break;
  }
  v3 ^= b; SIPROUND; SIPROUND; v0 ^= b; v2 ^= 0xff;
  SIPROUND; SIPROUND; SIPROUND; SIPROUND;
  return v0 ^ v1 ^ v2 ^ v3;
}

/* ---- MurmurHash3 x64_128, Appleby reference ---- */
static inline uint64_t fmix64(uint64_t k){k^=k>>33;k*=0xff51afd7ed558ccdULL;k^=k>>33;k*=0xc4ceb9fe1a85ec53ULL;k^=k>>33;return k;}
static void mm3_128(const void *key, int len, uint32_t seed, void *out) {
  const uint8_t *data=(const uint8_t*)key; const int nblocks=len/16;
  uint64_t h1=seed,h2=seed;
  const uint64_t c1=0x87c37b91114253d5ULL, c2=0x4cf5ad432745937fULL;
  const uint64_t *blocks=(const uint64_t*)(data);
  for(int i=0;i<nblocks;i++){
    uint64_t k1,k2; memcpy(&k1,blocks+i*2,8); memcpy(&k2,blocks+i*2+1,8);
    k1*=c1;k1=ROTL(k1,31);k1*=c2;h1^=k1;
    h1=ROTL(h1,27);h1+=h2;h1=h1*5+0x52dce729;
    k2*=c2;k2=ROTL(k2,33);k2*=c1;h2^=k2;
    h2=ROTL(h2,31);h2+=h1;h2=h2*5+0x38495ab5;
  }
  const uint8_t *tail=(const uint8_t*)(data+nblocks*16);
  uint64_t k1=0,k2=0;
  switch(len&15){
    case 15: k2^=((uint64_t)tail[14])<<48; /* fallthrough */
    case 14: k2^=((uint64_t)tail[13])<<40; /* fallthrough */
    case 13: k2^=((uint64_t)tail[12])<<32; /* fallthrough */
    case 12: k2^=((uint64_t)tail[11])<<24; /* fallthrough */
    case 11: k2^=((uint64_t)tail[10])<<16; /* fallthrough */
    case 10: k2^=((uint64_t)tail[9])<<8;   /* fallthrough */
    case  9: k2^=((uint64_t)tail[8]);
             k2*=c2;k2=ROTL(k2,33);k2*=c1;h2^=k2; /* fallthrough */
    case  8: k1^=((uint64_t)tail[7])<<56; /* fallthrough */
    case  7: k1^=((uint64_t)tail[6])<<48; /* fallthrough */
    case  6: k1^=((uint64_t)tail[5])<<40; /* fallthrough */
    case  5: k1^=((uint64_t)tail[4])<<32; /* fallthrough */
    case  4: k1^=((uint64_t)tail[3])<<24; /* fallthrough */
    case  3: k1^=((uint64_t)tail[2])<<16; /* fallthrough */
    case  2: k1^=((uint64_t)tail[1])<<8;  /* fallthrough */
    case  1: k1^=((uint64_t)tail[0]);
             k1*=c1;k1=ROTL(k1,31);k1*=c2;h1^=k1;
  }
  h1^=len;h2^=len;h1+=h2;h2+=h1;h1=fmix64(h1);h2=fmix64(h2);h1+=h2;h2+=h1;
  ((uint64_t*)out)[0]=h1;((uint64_t*)out)[1]=h2;
}

/* ---- xxHash64 ---- */
#define P1 0x9E3779B185EBCA87ULL
#define P2 0xC2B2AE3D27D4EB4FULL
#define P3 0x165667B19E3779F9ULL
#define P4 0x85EBCA77C2B2AE63ULL
#define P5 0x27D4EB2F165667C5ULL
static inline uint64_t rd8(const uint8_t*p){uint64_t v;memcpy(&v,p,8);return v;}
static inline uint32_t rd4(const uint8_t*p){uint32_t v;memcpy(&v,p,4);return v;}
static inline uint64_t rnd(uint64_t a,uint64_t v){a+=v*P2;a=ROTL(a,31);a*=P1;return a;}
static inline uint64_t mrg(uint64_t h,uint64_t v){v=rnd(0,v);h^=v;h=h*P1+P4;return h;}
static uint64_t xxh64(const uint8_t*p,size_t len,uint64_t seed){
  const uint8_t*e=p+len; uint64_t h;
  if(len>=32){const uint8_t*lim=e-32;uint64_t v1=seed+P1+P2,v2=seed+P2,v3=seed,v4=seed-P1;
    do{v1=rnd(v1,rd8(p));p+=8;v2=rnd(v2,rd8(p));p+=8;v3=rnd(v3,rd8(p));p+=8;v4=rnd(v4,rd8(p));p+=8;}while(p<=lim);
    h=ROTL(v1,1)+ROTL(v2,7)+ROTL(v3,12)+ROTL(v4,18);h=mrg(h,v1);h=mrg(h,v2);h=mrg(h,v3);h=mrg(h,v4);
  } else h=seed+P5;
  h+=(uint64_t)len;
  while(p+8<=e){h^=rnd(0,rd8(p));h=ROTL(h,27)*P1+P4;p+=8;}
  if(p+4<=e){h^=(uint64_t)rd4(p)*P1;h=ROTL(h,23)*P2+P3;p+=4;}
  while(p<e){h^=(*p++)*P5;h=ROTL(h,11)*P1;}
  h^=h>>33;h*=P2;h^=h>>29;h*=P3;h^=h>>32;return h;
}

static double now(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec+1e-9*t.tv_nsec;}

int main(void){
  uint8_t key[16]; for(int i=0;i<16;i++)key[i]=i;
  /* sanity: SipHash paper vector for the 15-byte message */
  { uint8_t m[15]; for(int i=0;i<15;i++)m[i]=i;
    uint64_t v=siphash24(m,15,key);
    printf("siphash sanity(15) = %016llx (paper LE e545be4961ca29a1 -> %s)\n",
      (unsigned long long)v, v==0xa129ca6149be45e5ULL?"OK":"MISMATCH"); }

  const size_t sizes[] = {38, 47, 60, 67};
  const char *labels[] = {"keyHash(16B key) 38B","ringToken(10B id) 47B","slotScore(10B id) 60B","rvScore(16B rk,10B id) 67B"};
  const long N = 30000000;
  uint8_t buf[128]; for(int i=0;i<128;i++) buf[i]=(uint8_t)(i*7+1);
  volatile uint64_t sink=0;

  printf("\n%-32s %10s %10s %10s\n","framed input","siphash24","mm3_x64","xxh64");
  for(int s=0;s<4;s++){
    size_t L=sizes[s]; double t; uint64_t o[2];
    t=now(); for(long i=0;i<N;i++){buf[0]=(uint8_t)i; sink^=siphash24(buf,L,key);} double a=(now()-t)/N*1e9;
    t=now(); for(long i=0;i<N;i++){buf[0]=(uint8_t)i; mm3_128(buf,(int)L,0x9747b28c,o); sink^=o[0];} double b=(now()-t)/N*1e9;
    t=now(); for(long i=0;i<N;i++){buf[0]=(uint8_t)i; sink^=xxh64(buf,L,0);} double c=(now()-t)/N*1e9;
    printf("%-32s %8.2fns %8.2fns %8.2fns   sip/mm3=%.1fx sip/xxh=%.1fx\n",labels[s],a,b,c,a/b,a/c);
  }
  printf("\n(sink %llu)\n",(unsigned long long)sink);
  return 0;
}
