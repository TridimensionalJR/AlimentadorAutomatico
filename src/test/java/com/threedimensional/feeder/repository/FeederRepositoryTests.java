package com.threedimensional.feeder.repository;

import com.threedimensional.feeder.model.Feeder;
import com.threedimensional.feeder.model.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The feeder table's own guarantees, mirroring what {@code FeederConfigRepositoryTests} does for
 * its table: the hardware pairing is unique, and the queries account deletion and device
 * management run return what they promise. {@code FeederTests} covers the domain side; this suite
 * proves the column-level rules hold in the database too.
 */
@DataJpaTest
class FeederRepositoryTests {

	private static final UUID DEVICE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

	@Autowired
	private FeederRepository feederRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager entityManager;

	private User user;

	@BeforeEach
	void setUp() {
		user = userRepository.saveAndFlush(User.create("google-abc", "tiago@example.com", "Tiago"));
	}

	/**
	 * One physical device can only ever be paired with one feeder. The domain has no way to
	 * express the collision, so the UNIQUE constraint is the real owner of the rule.
	 */
	@Test
	void save_shouldRejectSecondFeeder_withSameDeviceId() {
		feederRepository.saveAndFlush(Feeder.create(DEVICE_ID, "Alimentador da sala", null, user));

		assertThatThrownBy(() -> feederRepository.saveAndFlush(
				Feeder.create(DEVICE_ID, "Alimentador do quarto", null, user)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void findByUserId_shouldReturnOnlyThatUsersFeeders() {
		feederRepository.saveAndFlush(Feeder.create(DEVICE_ID, "Alimentador da sala", null, user));
		User other = userRepository.saveAndFlush(User.create("google-xyz", "bruna@example.com", "Bruna"));
		feederRepository.saveAndFlush(Feeder.create(
				UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"), "Alimentador do escritorio", null, other));

		assertThat(feederRepository.findByUserId(user.getId()))
				.singleElement()
				.extracting(Feeder::getName)
				.isEqualTo("Alimentador da sala");
	}

	@Test
	void existsByUserId_shouldReflectTheFeederOwnership() {
		User orphan = userRepository.saveAndFlush(User.create("google-xyz", "bruna@example.com", "Bruna"));
		feederRepository.saveAndFlush(Feeder.create(DEVICE_ID, "Alimentador da sala", null, user));

		assertThat(feederRepository.existsByUserId(user.getId())).isTrue();
		assertThat(feederRepository.existsByUserId(orphan.getId())).isFalse();
	}

	/**
	 * The zone has to survive the round trip, not just live in memory until the next flush:
	 * entityManager.clear() forces the read back from the column.
	 */
	@Test
	void findByUserId_shouldPersistACustomTimezone() {
		Feeder feeder = Feeder.create(DEVICE_ID, "Alimentador da sala", null, user);
		feeder.updateTimezone("America/Manaus");
		feederRepository.saveAndFlush(feeder);
		entityManager.clear();

		assertThat(feederRepository.findByUserId(user.getId()))
				.singleElement()
				.extracting(Feeder::getTimezone)
				.isEqualTo("America/Manaus");
	}
}