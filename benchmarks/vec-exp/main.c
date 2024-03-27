// Copyright 2022 ETH Zurich and University of Bologna.
//
// SPDX-License-Identifier: Apache-2.0
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// Author: Matteo Perotti

#include <stdint.h>
#include <string.h>

#include "exp.h"
#include "util.h"
#include "ara/util.h"
#include <stdio.h>

#define N_F64 128
#define N_F32 256

extern size_t N_f64;
extern double exponents_f64[] __attribute__((aligned(32)));
extern double results_f64[] __attribute__((aligned(32)));
extern double gold_results_f64[] __attribute__((aligned(32)));

extern size_t N_f32;
extern float exponents_f32[] __attribute__((aligned(32)));
extern float results_f32[] __attribute__((aligned(32)));
extern float gold_results_f32[] __attribute__((aligned(32)));

double results_f64m1[N_F64] __attribute__((aligned(32)));
double results_f64m2[N_F64] __attribute__((aligned(32)));
double results_f64m4[N_F64] __attribute__((aligned(32)));
float results_f32m1[N_F32] __attribute__((aligned(32)));
float results_f32m2[N_F32] __attribute__((aligned(32)));
float results_f32m4[N_F32] __attribute__((aligned(32)));


#define THRESHOLD 1.0

int check64(double* results) {
  int error = 0;
  for (uint64_t i = 0; i < N_f64; ++i) {
    if (!similarity_check(results[i], gold_results_f64[i], THRESHOLD)) {
      error = 1;
      printf("64-bit error at index %d. %lx != %lx\n", i, *(uint64_t*)(&results[i]),
             *(uint64_t*)(&gold_results_f64[i]));
    }
  }
  return error;
}

int check32(float* results) {
  int error = 0;
  for (uint64_t i = 0; i < N_f32; ++i) {
    if (!similarity_check(results[i], gold_results_f32[i], THRESHOLD)) {
      error = 1;
      printf("32-bit error at index %d. %x != %x\n", i, *(uint32_t*)(&results[i]),
	     *(uint32_t*)(&gold_results_f32[i]));
    }
  }
  return error;
}

int main() {
  if (N_F64 != N_f64 || N_F32 != N_f32) return 1;

  printf("FEXP\n");

  int error = 0;
  unsigned long cycles1, cycles2, instr2, instr1;

  printf("Executing exponential on %d 64-bit data LMUL=1...\n", N_f64);
  instr1 = read_csr(minstret);
  cycles1 = read_csr(mcycle);
  exp_f64m1_bmark(exponents_f64, results_f64m1, N_f64);
  asm volatile("fence");
  instr2 = read_csr(minstret);
  cycles2 = read_csr(mcycle);
  printf("The execution took %d cycles.\n", cycles2 - cycles1);

  printf("Executing exponential on %d 64-bit data LMUL=2...\n", N_f64);
  instr1 = read_csr(minstret);
  cycles1 = read_csr(mcycle);
  exp_f64m2_bmark(exponents_f64, results_f64m2, N_f64);
  asm volatile("fence");
  instr2 = read_csr(minstret);
  cycles2 = read_csr(mcycle);
  printf("The execution took %d cycles.\n", cycles2 - cycles1);

  printf("Executing exponential on %d 64-bit data LMUL=4...\n", N_f64);
  instr1 = read_csr(minstret);
  cycles1 = read_csr(mcycle);
  exp_f64m4_bmark(exponents_f64, results_f64m4, N_f64);
  asm volatile("fence");
  instr2 = read_csr(minstret);
  cycles2 = read_csr(mcycle);
  printf("The execution took %d cycles.\n", cycles2 - cycles1);

  printf("Executing exponential on %d 32-bit data LMUL=1...\n", N_f32);
  instr1 = read_csr(minstret);
  cycles1 = read_csr(mcycle);
  exp_f32m1_bmark(exponents_f32, results_f32m1, N_f32);
  asm volatile("fence");
  instr2 = read_csr(minstret);
  cycles2 = read_csr(mcycle);
  printf("The execution took %d cycles.\n", cycles2 - cycles1);

  printf("Executing exponential on %d 32-bit data LMUL=2...\n", N_f32);
  instr1 = read_csr(minstret);
  cycles1 = read_csr(mcycle);
  exp_f32m2_bmark(exponents_f32, results_f32m2, N_f32);
  asm volatile("fence");
  instr2 = read_csr(minstret);
  cycles2 = read_csr(mcycle);
  printf("The execution took %d cycles.\n", cycles2 - cycles1);

  printf("Executing exponential on %d 32-bit data LMUL=4...\n", N_f32);
  instr1 = read_csr(minstret);
  cycles1 = read_csr(mcycle);
  exp_f32m4_bmark(exponents_f32, results_f32m4, N_f32);
  asm volatile("fence");
  instr2 = read_csr(minstret);
  cycles2 = read_csr(mcycle);
  printf("The execution took %d cycles.\n", cycles2 - cycles1);

  printf("Checking results:\n");

  error = check64(results_f64m1); if (error) { return error; }
  error = check64(results_f64m2); if (error) { return error; }
  error = check64(results_f64m4); if (error) { return error; }
  error = check32(results_f32m1); if (error) { return error; }
  error = check32(results_f32m2); if (error) { return error; }
  error = check32(results_f32m4); if (error) { return error; }

  return error;
}
