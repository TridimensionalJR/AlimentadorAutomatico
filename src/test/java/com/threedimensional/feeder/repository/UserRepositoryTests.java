package com.threedimensional.feeder.repository;

import com.threedimensional.feeder.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The user table's two uniqueness rules and the natural-key lookup. Nothing here needs the
 * service layer: sign-in depends on google_id being unique and findable, and email carries its
 * own uniqueness because it is the address a person recognises as theirs.
 */
@DataJpaTest
class UserRepositoryTests {

	@Autowired
	private UserRepository userRepository;

	@Test
	void save_shouldRejectSecondAccount_withSameGoogleId() {
		userRepository.saveAndFlush(User.create("google-abc", "tiago@example.com", "Tiago"));

		assertThatThrownBy(() -> userRepository.saveAndFlush(User.create("google-abc", "bruna@example.com", "Bruna")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void save_shouldRejectSecondAccount_withSameEmail() {
		userRepository.saveAndFlush(User.create("google-abc", "bruna@example.com", "Tiago"));

		assertThatThrownBy(() -> userRepository.saveAndFlush(User.create("google-xyz", "bruna@example.com", "Bruna")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void save_shouldAcceptAccounts_thatDifferInBothIdentityFields() {
		userRepository.saveAndFlush(User.create("google-abc", "tiago@example.com", "Tiago"));

		User other = userRepository.saveAndFlush(User.create("google-xyz", "bruna@example.com", "Bruna"));

		assertThat(other.getId()).isNotNull();
	}

	/**
	 * The natural key the sign-in upsert searches by. The lookup happens before any write, so
	 * this pins that the column itself answers the query rather than some in-memory shortcut.
	 */
	@Test
	void findByGoogleId_shouldReturnTheAccount() {
		userRepository.saveAndFlush(User.create("google-abc", "tiago@example.com", "Tiago"));

		User found = userRepository.findByGoogleId("google-abc").orElseThrow();

		assertThat(found.getEmail()).isEqualTo("tiago@example.com");
		assertThat(found.getName()).isEqualTo("Tiago");
	}
}