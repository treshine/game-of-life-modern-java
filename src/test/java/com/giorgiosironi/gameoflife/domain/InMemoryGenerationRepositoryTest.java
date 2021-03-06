package com.giorgiosironi.gameoflife.domain;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.Test;

import com.giorgiosironi.gameoflife.domain.GenerationRepository.GenerationResult;
import com.giorgiosironi.gameoflife.domain.GenerationRepository.GenerationResult.Efficiency;

public class InMemoryGenerationRepositoryTest {
	
	InMemoryGenerationRepository repository = new InMemoryGenerationRepository();

	@Test
	public void storesAGenerationAccordingToANameAndIndex() {
		Generation single = Generation.withAliveCells(Cell.onXAndY(0, 1));
		repository.add("single", 4, single);
		assertEquals(
			GenerationResult.hit(single),
			repository.get("single", 4)
		);
	}
	
	@Test
	public void missingValuesReturnNull() {
		assertEquals(
			GenerationResult.miss(),
			repository.get("single", 4)
		);
	}
	
	@Test
	public void outdatedValuesCanBeUsedToCalculateAMoreRecentGeneration() {
		Generation single = Generation.withAliveCells(Cell.onXAndY(0, 1));
		repository.add("single", 3, single);
		assertEquals(
			GenerationResult.partialHit(Generation.empty(), 1),
			repository.get("single", 4)
		);
	}
	
	@Test
	public void theMostRecentGenerationOfAPlaneIsUsedToCalculateTheMoreRecentOne() {		
		repository.add("single", 2, Generation.withAliveCells(Cell.onXAndY(0, 1)));
		repository.add("single", 3, Generation.blockAt(0, 1));
		assertEquals(
			GenerationResult.partialHit(Generation.blockAt(0, 1), 2),
			repository.get("single", 5)
		);
	}
	
	@Test
	public void multipleGenerationsCanBeCachedTogether() {
		repository.add("single", 3, Generation.blockAt(0, 1));
		repository.add("single", 2, Generation.withAliveCells(Cell.onXAndY(0, 1)));
		assertEquals(
			GenerationResult.partialHit(Generation.blockAt(0, 1), 2),
			repository.get("single", 5)
		);
	}
	
	@Test
	public void previousGenerationsCannotBeCalculatedFromAMoreRecentOne() {
		repository.add("single", 4, Generation.withAliveCells(Cell.onXAndY(0, 1)));
		assertEquals(
			GenerationResult.miss(),
			repository.get("single", 3)
		);
	}
	
	int threads = 10;
	int iterations = 100;
	CyclicBarrier barrier = new CyclicBarrier(
		threads,
		() -> {
			//System.out.println("Round completed");
		}
	);
	
	@Test
	public void concurrentUsageOfPlanesDoNotInterfereWithEachOther() throws InterruptedException {
		ExecutorService executor = Executors.newCachedThreadPool();
		
		Generation block = Generation.blockAt(0, 1);
		Generation horizontalBar = Generation.horizontalBarAt(0, 1);
		Generation verticalBar = Generation.verticalBarAt(1, 0);
		final List<List<Efficiency>> efficiencySequences = Collections.synchronizedList(new ArrayList<>());
		final List<List<Integer>> calculationSequences = Collections.synchronizedList(new ArrayList<>());
		for (int i = 0; i < threads; i++) {
			String plane = "plane-" + i;
			List<Efficiency> efficiencySequence = Collections.synchronizedList(new ArrayList<>());
			efficiencySequences.add(efficiencySequence);
			List<Integer> calculationSequence = Collections.synchronizedList(new ArrayList<>());
			calculationSequences.add(calculationSequence);
			if (i % 2 == 0) {
				executor.execute(new SinglePlaneUser(
					block,
					plane,
					(j) -> {
						GenerationResult result = repository.get(plane, j);
						assertEquals(
							block,
							result.generation()
						);
						efficiencySequence.add(result.efficiency());
						calculationSequence.add(result.calculations());
					}
				));
			} else {
				executor.execute(new SinglePlaneUser(
					horizontalBar,
					plane,
					(j) -> {
						GenerationResult result = repository.get(plane, j);
						if (j % 2 == 0) {
							assertEquals(
								horizontalBar,
								result.generation()
							);
						} else {
							assertEquals(
								verticalBar,
								result.generation()
							);
						}
						efficiencySequence.add(result.efficiency());
						calculationSequence.add(result.calculations());
					}
				));
			}
		}
		executor.shutdown();
		executor.awaitTermination(10, TimeUnit.SECONDS);
		for (List<Efficiency> sequence: efficiencySequences) {
			assertEquals(iterations, sequence.size());
			assertEquals(Efficiency.HIT, sequence.get(0));
			for (int i = 1; i < sequence.size(); i++) {
				assertEquals(Efficiency.PARTIAL_HIT, sequence.get(i));
			}
		}
		for (List<Integer> sequence: calculationSequences) {
			assertEquals(iterations, sequence.size());
			for (int i = 1; i < sequence.size(); i++) {
				assertTrue(
					"Only one step of evolution should be performed when accessing serially a plane's generations: " + sequence,
					sequence.get(i).equals(1)
				);
			}
		}
	}
	
	private class SinglePlaneUser implements Runnable {

		private String plane;
		private Generation startingPoint;
		private Consumer<Integer> check;
		
		private SinglePlaneUser(Generation startingPoint, String plane, Consumer<Integer> check) {
			this.startingPoint = startingPoint;
			this.plane = plane;
			this.check = check;
		}
		
		@Override
		public void run() {
			try {
				barrier.await();
				repository.add(plane, 0, startingPoint);
				for (int j = 0; j < iterations; j++) {
					barrier.await();
					check.accept(j);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			
		}	
	}
	
	// takes very long to run
	//@Test
	public void concurrentAccessesToTheSamePlaneNeverLosesReturnedGenerationsAsFutureSureHits() throws InterruptedException {
		iterations = 1000;
		int generations = 100;
		for (int k = 0; k < iterations; k++) {
			ExecutorService executor = Executors.newCachedThreadPool();
			final String plane = "sample-" + k; 
			repository.add(plane, 0, Generation.blockAt(0, 1));
			barrier = new CyclicBarrier(threads);
			final List<String> errors  = Collections.synchronizedList(new ArrayList<>());
			for (int i = 0; i < threads; i++) {
				executor.execute(() -> {
					try {
						for (int j = 0; j < generations; j++) {
							Random random = new Random();
							int index = random.nextInt(10000) + 1;
							barrier.await();
							GenerationResult firstTry = repository.get(plane, index);
							if (firstTry.efficiency().equals(Efficiency.MISS)) {
								errors.add("The generation at index " + index + " is missing");
							}
							GenerationResult secondTry = repository.get(plane, index);
							if (!Efficiency.HIT.equals(secondTry.efficiency())){
								errors.add(
									"Error while retrieving generation the 2nd time for index "
									+ index
									+ ". Efficiency was "
									+ secondTry.efficiency()
								);
							}
						}
					} catch (Exception e) {
						e.printStackTrace();
					}		
				});
			}
			executor.shutdown();
			executor.awaitTermination(10, TimeUnit.SECONDS);
			if (errors.size() > 0) {
				fail(errors.toString());
			}
		}
	}
}
