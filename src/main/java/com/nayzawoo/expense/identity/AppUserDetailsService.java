package com.nayzawoo.expense.identity;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return users.findByEmail(normalize(username))
                .map(ExpenseUser::new)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }

    static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
