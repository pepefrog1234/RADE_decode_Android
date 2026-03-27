/*
 * When linking against libopus.a (which provides the real nnet functions
 * from Opus's dnn/nnet.c and dnn/parse_lpcnet_weights.c), this standalone
 * reimplementation must be disabled to avoid duplicate symbols.
 */
#ifdef USE_LIBOPUS
/* Empty: all nnet functions are provided by libopus.a */
#else

/* Copyright (c) 2018 Mozilla
   Copyright (c) 2017 Jean-Marc Valin */
/*
   Redistribution and use in source and binary forms, with or without
   modification, are permitted provided that the following conditions
   are met:

   - Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.

   - Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
   ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
   LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
   A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE FOUNDATION OR
   CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
   EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
   PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
   PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
   LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
   NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
   SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

/*
 * rade_nnet.c - Standalone neural network implementation for the RADE library.
 *
 * Based on Opus's dnn/nnet.c and dnn/parse_lpcnet_weights.c but modified
 * to avoid depending on the full Opus library. Provides compute_linear_c,
 * compute_activation_c, compute_conv2d_c, compute_generic_dense,
 * compute_generic_gru, compute_generic_conv1d, compute_generic_conv1d_dilation,
 * compute_glu, compute_gated_activation, parse_weights, linear_init,
 * and conv2d_init.
 */

#ifdef HAVE_CONFIG_H
#include "config.h"
#endif

#include <math.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>

#include "arch.h"
#include "os_support.h"
#include "opus_types.h"
#include "rade_constants.h"

/* Local allocation wrappers to avoid name conflicts with os_support.h's
   opus_alloc/opus_free (which are static inline and cannot be redefined). */
static inline void *opus_alloc_local(size_t size) { return malloc(size); }
static inline void opus_free_local(void *ptr) { free(ptr); }
static inline void *opus_realloc_local(void *ptr, size_t size) { return realloc(ptr, size); }

/* Constants */
#define MAX_RNN_NEURONS_ALL RADE_ENC_MAX_RNN_NEURONS
#define MAX_CONV_INPUTS_ALL RADE_ENC_MAX_CONV_INPUTS
#define MAX_INPUTS 2048
#define SPARSE_BLOCK_SIZE 32

/*
 * Include nnet.h to get the struct definitions (LinearLayer, Conv2dLayer,
 * WeightArray, WeightHead) and the activation constants. The _c functions
 * are declared in nnet.h, and the macros that redirect compute_linear ->
 * compute_linear_c etc. are defined after the declarations, so our function
 * definitions below will compile correctly.
 */
#include "nnet.h"


/* ===================================================================== */
/*                          ACTIVATION HELPERS                           */
/* ===================================================================== */

static inline float sigmoid_approx(float x) {
    if (x < -8.f) return 0.f;
    if (x > 8.f) return 1.f;
    return 1.f / (1.f + expf(-x));
}

static inline float tansig_approx(float x) {
    if (x < -8.f) return -1.f;
    if (x > 8.f) return 1.f;
    return tanhf(x);
}

static inline float relu_fn(float x) {
    return x > 0.f ? x : 0.f;
}


/* ===================================================================== */
/*                          compute_activation_c                         */
/* ===================================================================== */

void compute_activation_c(float *output, const float *input, int N, int activation)
{
    int i;
    if (activation == ACTIVATION_SIGMOID) {
        for (i = 0; i < N; i++) {
            output[i] = sigmoid_approx(input[i]);
        }
    } else if (activation == ACTIVATION_TANH) {
        for (i = 0; i < N; i++) {
            output[i] = tansig_approx(input[i]);
        }
    } else if (activation == ACTIVATION_RELU) {
        for (i = 0; i < N; i++) {
            output[i] = relu_fn(input[i]);
        }
    } else if (activation == ACTIVATION_SOFTMAX) {
        float sum = 0.f;
        float max_val = input[0];
        for (i = 1; i < N; i++) {
            if (input[i] > max_val) max_val = input[i];
        }
        for (i = 0; i < N; i++) {
            output[i] = expf(input[i] - max_val);
            sum += output[i];
        }
        if (sum > 0.f) {
            float inv_sum = 1.f / sum;
            for (i = 0; i < N; i++) {
                output[i] *= inv_sum;
            }
        }
    } else if (activation == ACTIVATION_SWISH) {
        for (i = 0; i < N; i++) {
            output[i] = input[i] * sigmoid_approx(input[i]);
        }
    } else if (activation == ACTIVATION_EXP) {
        for (i = 0; i < N; i++) {
            output[i] = expf(input[i]);
        }
    } else {
        /* ACTIVATION_LINEAR or unknown: pass through */
        if (output != input) {
            for (i = 0; i < N; i++) {
                output[i] = input[i];
            }
        }
    }
}


/* ===================================================================== */
/*                          compute_linear_c                             */
/* ===================================================================== */

void compute_linear_c(const LinearLayer *linear, float *out, const float *in)
{
    int i, j;
    int N = linear->nb_outputs;
    int M = linear->nb_inputs;
    const float *bias = linear->bias;

    /* Initialize output with bias */
    if (bias != NULL) {
        for (i = 0; i < N; i++) {
            out[i] = bias[i];
        }
    } else {
        for (i = 0; i < N; i++) {
            out[i] = 0.f;
        }
    }

    /* Diagonal (sparse) mode */
    if (linear->diag != NULL) {
        const float *diag = linear->diag;
        celt_assert(N == M);
        for (i = 0; i < N; i++) {
            out[i] += diag[i] * in[i];
        }
        return;
    }

    /* Dense float weights */
    if (linear->float_weights != NULL) {
        const float *w = linear->float_weights;
        for (i = 0; i < N; i++) {
            float sum = out[i];
            for (j = 0; j < M; j++) {
                sum += w[i * M + j] * in[j];
            }
            out[i] = sum;
        }
        return;
    }

    /* Quantized int8 weights with scale */
    if (linear->weights != NULL && linear->scale != NULL) {
        const opus_int8 *w = linear->weights;
        const float *scale = linear->scale;
        const float *subias = linear->subias;

        /* Add subias if present */
        if (subias != NULL) {
            for (i = 0; i < N; i++) {
                out[i] += subias[i];
            }
        }

        if (linear->weights_idx != NULL) {
            /* Sparse int8: column indices in weights_idx */
            const int *idx = linear->weights_idx;
            int nb_blocks = M / SPARSE_BLOCK_SIZE;

            for (i = 0; i < N; i++) {
                float sum = 0.f;
                for (j = 0; j < nb_blocks; j++) {
                    int col_start = idx[i * nb_blocks + j] * SPARSE_BLOCK_SIZE;
                    int k;
                    for (k = 0; k < SPARSE_BLOCK_SIZE; k++) {
                        sum += (float)w[i * M + j * SPARSE_BLOCK_SIZE + k] * in[col_start + k];
                    }
                }
                out[i] += sum * scale[i];
            }
        } else {
            /* Dense int8 weights with scale */
            for (i = 0; i < N; i++) {
                float sum = 0.f;
                for (j = 0; j < M; j++) {
                    sum += (float)w[i * M + j] * in[j];
                }
                out[i] += sum * scale[i];
            }
        }
        return;
    }
}


/* ===================================================================== */
/*                          compute_conv2d_c                             */
/* ===================================================================== */

void compute_conv2d_c(const Conv2dLayer *conv, float *out, float *mem, const float *in,
                      int height, int hstride, int activation)
{
    int i, j, kh, kt;
    int in_channels = conv->in_channels;
    int out_channels = conv->out_channels;
    int ktime = conv->ktime;
    int kheight = conv->kheight;
    const float *bias = conv->bias;
    const float *w = conv->float_weights;

    for (i = 0; i < out_channels; i++) {
        for (j = 0; j < height; j++) {
            float sum = 0.f;
            if (bias != NULL) {
                sum = bias[i];
            }
            for (kt = 0; kt < ktime; kt++) {
                const float *src;
                if (kt < ktime - 1) {
                    src = &mem[kt * in_channels * hstride];
                } else {
                    src = in;
                }
                for (kh = 0; kh < kheight; kh++) {
                    int h_in = j + kh - kheight / 2;
                    if (h_in >= 0 && h_in < height) {
                        int ic;
                        for (ic = 0; ic < in_channels; ic++) {
                            int w_idx = i * (ktime * kheight * in_channels)
                                      + kt * (kheight * in_channels)
                                      + kh * in_channels
                                      + ic;
                            sum += w[w_idx] * src[h_in * in_channels + ic];
                        }
                    }
                }
            }
            out[j * out_channels + i] = sum;
        }
    }

    /* Shift memory */
    if (ktime > 1) {
        for (i = 0; i < ktime - 2; i++) {
            OPUS_COPY(&mem[i * in_channels * hstride],
                      &mem[(i + 1) * in_channels * hstride],
                      in_channels * hstride);
        }
        OPUS_COPY(&mem[(ktime - 2) * in_channels * hstride], in, in_channels * hstride);
    }

    compute_activation_c(out, out, out_channels * height, activation);
}


/* ===================================================================== */
/*                     HIGH-LEVEL COMPUTE FUNCTIONS                      */
/* ===================================================================== */

void compute_generic_dense(const LinearLayer *layer, float *output, const float *input,
                           int activation, int arch)
{
    int N = layer->nb_outputs;
    compute_linear(layer, output, input, arch);
    compute_activation(output, output, N, activation, arch);
}

void compute_generic_gru(const LinearLayer *input_weights, const LinearLayer *recurrent_weights,
                         float *state, const float *in, int arch)
{
    int i;
    int N = recurrent_weights->nb_inputs;
    float zrh[MAX_RNN_NEURONS_ALL * 3];
    float recur[MAX_RNN_NEURONS_ALL * 3];

    celt_assert(N <= MAX_RNN_NEURONS_ALL);

    /* Input contribution: Wz*x, Wr*x, Wh*x */
    compute_linear(input_weights, zrh, in, arch);

    /* Recurrent contribution: Uz*h, Ur*h, Uh*h */
    compute_linear(recurrent_weights, recur, state, arch);

    /* z = sigmoid(Wz*x + Uz*h), r = sigmoid(Wr*x + Ur*h) */
    for (i = 0; i < 2 * N; i++) {
        zrh[i] += recur[i];
    }
    compute_activation(zrh, zrh, 2 * N, ACTIVATION_SIGMOID, arch);

    /* h_tilde = tanh(Wh*x + r * Uh*h) */
    for (i = 0; i < N; i++) {
        zrh[2 * N + i] += zrh[N + i] * recur[2 * N + i];
    }
    compute_activation(&zrh[2 * N], &zrh[2 * N], N, ACTIVATION_TANH, arch);

    /* state = z * state + (1 - z) * h_tilde */
    for (i = 0; i < N; i++) {
        state[i] = zrh[i] * state[i] + (1.f - zrh[i]) * zrh[2 * N + i];
    }
}

void compute_generic_conv1d(const LinearLayer *layer, float *output, float *mem,
                            const float *input, int input_size, int activation, int arch)
{
    float tmp[MAX_CONV_INPUTS_ALL * 2];
    int N = layer->nb_outputs;

    celt_assert(input_size <= MAX_CONV_INPUTS_ALL);

    /* Conv1d kernel_size=2: [mem | input] */
    OPUS_COPY(tmp, mem, input_size);
    OPUS_COPY(&tmp[input_size], input, input_size);

    /* Save current input for next call */
    OPUS_COPY(mem, input, input_size);

    compute_linear(layer, output, tmp, arch);
    compute_activation(output, output, N, activation, arch);
}

void compute_generic_conv1d_dilation(const LinearLayer *layer, float *output, float *mem,
                                     const float *input, int input_size,
                                     int dilation, int activation, int arch)
{
    float tmp[MAX_CONV_INPUTS_ALL * 2];
    int N = layer->nb_outputs;

    celt_assert(input_size <= MAX_CONV_INPUTS_ALL);

    /* Dilated conv1d kernel_size=2: [oldest_mem | current_input] */
    OPUS_COPY(tmp, mem, input_size);
    OPUS_COPY(&tmp[input_size], input, input_size);

    /* Shift memory and store current input */
    if (dilation > 1) {
        OPUS_MOVE(mem, &mem[input_size], (dilation - 1) * input_size);
    }
    OPUS_COPY(&mem[(dilation - 1) * input_size], input, input_size);

    compute_linear(layer, output, tmp, arch);
    compute_activation(output, output, N, activation, arch);
}


/* ===================================================================== */
/*                        GLU AND GATED ACTIVATION                       */
/* ===================================================================== */

void compute_glu(const LinearLayer *layer, float *output, const float *input, int arch)
{
    int i;
    int N = layer->nb_outputs;
    int output_size = N / 2;
    float tmp[MAX_RNN_NEURONS_ALL];

    celt_assert(N <= MAX_RNN_NEURONS_ALL);

    compute_linear(layer, tmp, input, arch);

    /* GLU: output[i] = a[i] * sigmoid(b[i]) */
    for (i = 0; i < output_size; i++) {
        output[i] = tmp[i] * sigmoid_approx(tmp[output_size + i]);
    }
}

void compute_gated_activation(const LinearLayer *layer, float *output, const float *input,
                              int activation, int arch)
{
    int i;
    int N = layer->nb_outputs;
    int output_size = N / 2;
    float tmp[MAX_RNN_NEURONS_ALL];

    celt_assert(N <= MAX_RNN_NEURONS_ALL);

    compute_linear(layer, tmp, input, arch);

    compute_activation(tmp, tmp, output_size, activation, arch);
    compute_activation(&tmp[output_size], &tmp[output_size], output_size, ACTIVATION_SIGMOID, arch);

    for (i = 0; i < output_size; i++) {
        output[i] = tmp[i] * tmp[output_size + i];
    }
}


/* ===================================================================== */
/*                    WEIGHT PARSING AND INITIALIZATION                  */
/* ===================================================================== */

static const void *find_array_entry(const WeightArray *arrays, const char *name)
{
    int i;
    if (name == NULL || arrays == NULL) return NULL;
    for (i = 0; arrays[i].name != NULL; i++) {
        if (strcmp(arrays[i].name, name) == 0) {
            return arrays[i].data;
        }
    }
    return NULL;
}

static int find_array_check(const WeightArray *arrays, const char *name, const void **out)
{
    *out = find_array_entry(arrays, name);
    if (*out == NULL) {
        fprintf(stderr, "find_array_check: cannot find array '%s'\n", name ? name : "(null)");
        return 1;
    }
    return 0;
}

static int opt_array_check(const WeightArray *arrays, const char *name, const void **out)
{
    if (name == NULL) {
        *out = NULL;
        return 0;
    }
    *out = find_array_entry(arrays, name);
    return 0;
}

static int find_idx_check(const WeightArray *arrays, const char *name)
{
    int i;
    if (name == NULL || arrays == NULL) return -1;
    for (i = 0; arrays[i].name != NULL; i++) {
        if (strcmp(arrays[i].name, name) == 0) {
            return i;
        }
    }
    return -1;
}


/* ===================================================================== */
/*                          parse_record                                  */
/* ===================================================================== */

static int parse_record(WeightArray *entry, const unsigned char *data, int len)
{
    const WeightHead *head;
    int data_bytes;
    int total;

    if (len < (int)sizeof(WeightHead)) return -1;

    head = (const WeightHead *)data;

    if (head->head[0] != 'w' || head->head[1] != 'g' ||
        head->head[2] != 'h' || head->head[3] != 't') {
        return -1;
    }

    if (head->version != WEIGHT_BLOB_VERSION) {
        fprintf(stderr, "parse_record: unsupported weight blob version %d\n", head->version);
        return -1;
    }

    switch (head->type) {
        case WEIGHT_TYPE_float:
            data_bytes = head->size * (int)sizeof(float);
            break;
        case WEIGHT_TYPE_int:
            data_bytes = head->size * (int)sizeof(int);
            break;
        case WEIGHT_TYPE_int8:
            data_bytes = head->size * (int)sizeof(opus_int8);
            break;
        case WEIGHT_TYPE_qweight:
            data_bytes = head->size;
            break;
        default:
            fprintf(stderr, "parse_record: unknown weight type %d\n", head->type);
            return -1;
    }

    /* Pad to int alignment */
    data_bytes = (data_bytes + (int)sizeof(int) - 1) & ~((int)sizeof(int) - 1);
    total = (int)sizeof(WeightHead) + data_bytes;

    if (len < total) {
        fprintf(stderr, "parse_record: truncated data for '%s' (need %d, have %d)\n",
                head->name, total, len);
        return -1;
    }

    entry->name = head->name;
    entry->type = head->type;
    entry->size = head->size;
    entry->data = data + sizeof(WeightHead);

    return total;
}


/* ===================================================================== */
/*                          parse_weights                                 */
/* ===================================================================== */

int parse_weights(WeightArray **list, const void *data, int len)
{
    const unsigned char *ptr = (const unsigned char *)data;
    int remaining = len;
    int capacity = 32;
    int count = 0;
    WeightArray *arrays;

    arrays = (WeightArray *)opus_alloc_local(capacity * sizeof(WeightArray));
    if (arrays == NULL) return -1;

    while (remaining > 0) {
        int consumed;

        if (count >= capacity - 1) {
            capacity *= 2;
            arrays = (WeightArray *)opus_realloc_local(arrays, capacity * sizeof(WeightArray));
            if (arrays == NULL) return -1;
        }

        consumed = parse_record(&arrays[count], ptr, remaining);
        if (consumed < 0) break;

        ptr += consumed;
        remaining -= consumed;
        count++;
    }

    /* Null terminator */
    arrays[count].name = NULL;
    arrays[count].type = 0;
    arrays[count].size = 0;
    arrays[count].data = NULL;

    *list = arrays;
    return count;
}


/* ===================================================================== */
/*                          linear_init                                   */
/* ===================================================================== */

int linear_init(LinearLayer *layer, const WeightArray *arrays,
                const char *bias,
                const char *subias,
                const char *weights,
                const char *float_weights,
                const char *weights_idx,
                const char *diag,
                const char *scale,
                int nb_inputs,
                int nb_outputs)
{
    memset(layer, 0, sizeof(*layer));
    layer->nb_inputs = nb_inputs;
    layer->nb_outputs = nb_outputs;

    if (bias != NULL) {
        const void *p = find_array_entry(arrays, bias);
        if (p == NULL) {
            fprintf(stderr, "linear_init: cannot find bias '%s'\n", bias);
            return 1;
        }
        layer->bias = (const float *)p;
    }

    if (subias != NULL) {
        layer->subias = (const float *)find_array_entry(arrays, subias);
    }

    if (weights != NULL) {
        layer->weights = (const opus_int8 *)find_array_entry(arrays, weights);
    }

    if (float_weights != NULL) {
        layer->float_weights = (const float *)find_array_entry(arrays, float_weights);
    }

    if (weights_idx != NULL) {
        layer->weights_idx = (const int *)find_array_entry(arrays, weights_idx);
    }

    if (diag != NULL) {
        layer->diag = (const float *)find_array_entry(arrays, diag);
    }

    if (scale != NULL) {
        layer->scale = (const float *)find_array_entry(arrays, scale);
    }

    if (layer->float_weights == NULL && layer->weights == NULL && layer->diag == NULL) {
        fprintf(stderr, "linear_init: no weights found for layer (bias='%s')\n",
                bias ? bias : "(null)");
        return 1;
    }

    return 0;
}


/* ===================================================================== */
/*                          conv2d_init                                   */
/* ===================================================================== */

int conv2d_init(Conv2dLayer *layer, const WeightArray *arrays,
                const char *bias,
                const char *float_weights,
                int in_channels,
                int out_channels,
                int ktime,
                int kheight)
{
    memset(layer, 0, sizeof(*layer));
    layer->in_channels = in_channels;
    layer->out_channels = out_channels;
    layer->ktime = ktime;
    layer->kheight = kheight;

    if (bias != NULL) {
        const void *p = find_array_entry(arrays, bias);
        if (p == NULL) {
            fprintf(stderr, "conv2d_init: cannot find bias '%s'\n", bias);
            return 1;
        }
        layer->bias = (const float *)p;
    }

    if (float_weights != NULL) {
        const void *p = find_array_entry(arrays, float_weights);
        if (p == NULL) {
            fprintf(stderr, "conv2d_init: cannot find weights '%s'\n", float_weights);
            return 1;
        }
        layer->float_weights = (const float *)p;
    }

    return 0;
}

#endif /* USE_LIBOPUS */
