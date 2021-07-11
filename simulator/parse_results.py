output_file = "output.csv"

if __name__ == "__main__":
    with open(output_file, 'r') as f:
        next(f)
        for line in f:
            line = line.split(',')
            policy = line[0]
            hits = int(line[2])
            batchHits = list(map(int, line[-1][1:-2].split(' ')))
            assert(hits == sum(batchHits))
            print(f'{policy}, {hits} = sum({batchHits})')
